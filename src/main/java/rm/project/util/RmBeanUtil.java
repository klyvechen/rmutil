package rm.project.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.proxy.HibernateProxy;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.FatalBeanException;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

public class RmBeanUtil {
	static final Logger logger = LogManager.getLogger(RmBeanUtil.class);

	private static int MAX_CHECK_LEVEL = 10;

	private static int checkLevel = 0;

	public static void copyProperties(Object source, Object target) {
		copyProperties(source, target, null, null);
	}

	/**
	 * 改寫Spring BeanUtil: 這邊主要改寫Spring BeanUtil的內容, 主要再複製欄位時, Enum與String的轉換
	 * 如果兩邊Bean裡面有相同的Field欄位, 但一邊為String另外一邊為Enum, 則會將該欄位值轉換成對應Enum值.
	 * 若是找不到對應Enum值若是有default值則會轉換成default值否則為空值(外加功能).
	 * 目前不對稱轉換只有target有設定欄位值可有Enum功能.
	 *
	 * @param source
	 * @param target
	 * @param editable
	 * @param ignoreProperties
	 */
	private static void copyProperties(Object source, Object target, @Nullable Class<?> editable, @Nullable String... ignoreProperties) {
		Assert.notNull(source, "Source must not be null");
		Assert.notNull(target, "Target must not be null");

		Class<?> actualEditable = target.getClass();
		if (editable != null) {
			if (!editable.isInstance(target)) {
				throw new IllegalArgumentException("Target class [" + target.getClass().getName() +
						"] not assignable to Editable class [" + editable.getName() + "]");
			}
			actualEditable = editable;
		}
		PropertyDescriptor[] targetPds = BeanUtils.getPropertyDescriptors(actualEditable);
		List<String> ignoreList = (ignoreProperties != null ? Arrays.asList(ignoreProperties) : null);

		for (PropertyDescriptor targetPd : targetPds) {
			Method writeMethod = targetPd.getWriteMethod();
			if (writeMethod != null && (ignoreList == null || !ignoreList.contains(targetPd.getName()))) {
				PropertyDescriptor sourcePd = BeanUtils.getPropertyDescriptor(source.getClass(), targetPd.getName());
				if (sourcePd != null) {
					Method readMethod = sourcePd.getReadMethod();
					try {
						if (readMethod != null &&
							ClassUtils.isAssignable(writeMethod.getParameterTypes()[0], readMethod.getReturnType())) {
							if (!Modifier.isPublic(readMethod.getDeclaringClass().getModifiers())) {
								readMethod.setAccessible(true);
							}
							Object value = readMethod.invoke(source);
							if (!Modifier.isPublic(writeMethod.getDeclaringClass().getModifiers())) {
								writeMethod.setAccessible(true);
							}
							writeMethod.invoke(target, value);
						} else {
							Object value = readMethod.invoke(source);
							if (writeMethod.getParameterTypes()[0].isEnum() && value instanceof String) {
								List enums = Arrays.asList(writeMethod.getParameterTypes()[0].getEnumConstants());
								Object enumValue = enums.stream().filter(e -> ((Enum) e).name().equals(value))
										.findFirst().orElse(null);
								if (enumValue != null) {
									writeMethod.invoke(target, enumValue);
								}
							}
						}
					} catch (Throwable ex) {
						throw new FatalBeanException("Could not copy property '" + targetPd.getName() + "' from source to target", ex);
					}
				}
			}
		}
	}

	/**
	 * The method can compare two objects with same data structure, field names but not the same class.
	 * If these two objects has the same value corresponding to each field, then return true, else false.
	 * The comparing mechanism are:
	 * 1. If the type of field is not the java.lang, or other condition in cannotGoDeeper, than go to the deeper layer and do again.
	 * 2. If the value is null, convert it to 0;
	 * Until all the comparisons are equal, then return true, else false.
	 * 本函式可以比較兩個有相同資料結構旦分屬不同類別的物件,
	 * 若比較的物件有Field不相同則會將不相同的值存入NdsCompareResult並回傳.
	 * @param t1 比較參數1
	 * @param t2 比較參數2
	 * @param <T> 比較類別
	 * @return true if two objects are total the same.
	 * @throws Exception Exception
	 */
	public static <T> RmCompareResult getDifferentValues(final T t1, final T t2) throws Exception {
		if (t1 == null && t2 == null) {
			return RmCompareResult.builder().same(true).build();
		}
		RmCompareResult result = preCheckBeans(t1, t2);
		if (!result.isSame()) {
			return result;
		}
		BeanInfo beanInfo1 = Introspector.getBeanInfo(t1.getClass());
		Map<String, Object> fieldMapping = new HashMap<>();
		for (PropertyDescriptor pd : beanInfo1.getPropertyDescriptors()) {
			fieldMapping.put(pd.getName(), pd.getReadMethod().invoke(t1));
		}
		BeanInfo beanInfo2 = Introspector.getBeanInfo(t2.getClass());
		for (PropertyDescriptor pd : beanInfo2.getPropertyDescriptors()) {
			if (isNdsIgnoreCompare(t2, pd)) {
				continue;
			}
			Object o1 = fieldMapping.get(pd.getName());
			Object o2 = pd.getReadMethod().invoke(t2);
			Class pdClazz = pd.getPropertyType();
			if (o1 != null && !isNativeClass(o1) && !cannotGoDeeper(o1)) {
				checkLevel++;
				RmCompareResult childResult = getDifferentValues(o1, o2);
				checkLevel--;
				if (!childResult.isSame()) {
					result.addCompareResult(pd.getName(), childResult);
				}
			} else if (!checkEqualityNullAsZero(o1, o2, pdClazz) && !isClassObject(o1)) {
				logger.warn("RmBeanUtil: " + pd.getName() + " are not equal: value1 " + getZeroValueAsNull(o1, pdClazz) + " and value2 " + getZeroValueAsNull(o2, pdClazz));
				result.addDiff(pd.getName(), o1, o2);
			}
		}
		return result;
	}

	public static <T> boolean compareJpaCollections(Collection<T> c1, Collection<T> c2) throws Exception {
		boolean same = true;
		Collection<T> lc, sc;
		if (c1.size() == c2.size()) {
			logger.debug("Two collections has same size {}", c1.size());
			lc = c1;
			sc = c2;
		} else if (c1.size() > c2.size()) {
			logger.debug("First collection is larger then second one");
			lc = c1;
			sc = c2;
			same = false;
		} else {
			logger.debug("First collection is smaller then second one");
			lc = c2;
			sc = c1;
			same = false;
		}
		if (sc.size() == 0) {
			logger.debug("No need to compare two sets");
			return false;
		}
		for (T o1 : lc) {
			boolean hasSameEntity = false;
			for (T o2 : sc) {
				if (isSameJpaEntity(o1, o2)) {
					RmCompareResult result = getDifferentValues(o1, o2);
					hasSameEntity = result.isSame();
					break;
				}
			}
			same &= hasSameEntity;
		}
		return same;
	}

	/**
	 * The method can compare two objects with same data structure, field names but not the same class.
	 * If these two objects has the same value corresponding to each field, then return true, else false.
	 * The comparing mechanism are:
	 * 1. If the type of field is not the java.lang, or other condition in cannotGoDeeper, than go to the deeper layer and do again.
	 * 2. If the value is null, convert it to 0;
	 * Until all the comparisons are equal, then return true, else false.
	 * 本函式可以比較兩個有相同資料結構旦分屬不同類別的物件, 若兩資料在所有相同的屬性所對應的值為相同, 則回傳真, 反之為假
	 * 本函式為使用getDifferentValues的結果,
	 * @param t1 比較參數1
	 * @param t2 比較參數2
	 * @param <T> 比較類別
	 * @return true if two objects are total the same.
	 * @throws Exception Exception
	 */
	public static <T> boolean haveSamePropertyValues (final T t1, final T t2) throws Exception {
		return getDifferentValues(t1, t2).isSame();
	}

	//Because the two objects are belong to different package, so we skip to check the class.
	private static boolean checkEqualityNullAsZero(final Object o1, final Object o2, final Class clazz) {
		return Objects.equals(getZeroValueAsNull(o1, clazz), getZeroValueAsNull(o2, clazz));
	}

	private static boolean isNdsIgnoreCompare(Object o, PropertyDescriptor pd) {
		boolean result = false;
		try {
			if (o instanceof HibernateProxy) {
				o = ((HibernateProxy) o).getHibernateLazyInitializer().getImplementation();
			}
			result = o.getClass().getDeclaredField(pd.getName()).getAnnotation(RmIgnoreCompare.class) != null;
		} catch (NoSuchFieldException e) {
			logger.debug("In get RmIgnoreCompare: cannot get {} from {}", pd.getName(), o);
		}
		return result;
	}

	private static <T> RmCompareResult preCheckBeans(final T t1, final T t2) {
		RmCompareResult result = RmCompareResult.builder().same(false).build();
		if (checkLevel == MAX_CHECK_LEVEL) {
			logger.warn("RmBeanUtil: t1 and t2 to maximum check level, return false");
			return result;
		}
		if (t1 == null) {
			logger.warn("t1 is null, return false");
			result.addDiff(t2.getClass().getName(), t1, t2);
			return result;
		}
		if (t2 == null) {
			logger.warn("t2 is null, return false");
			result.addDiff(t1.getClass().getName(), t1, t2);
			return result;
		}
		result.setSame(true);
		return result;
	}

	private static Object getZeroValueAsNull(final Object o, final Class clazz) {
		if (o != null)
			return o;
		if (clazz.equals(String.class)) {
			return "0";
		} else if (clazz.equals(Integer.class) || clazz.equals(Long.class) || clazz.equals(Double.class)) {
			return 0;
		} else {
			return o;
		}
	}

	private static boolean isClassObject(final Object o) {
		return o != null && "java.lang.Class".equals(o.getClass().getName());
	}

	private static boolean isNativeClass(final Object o) {
		return o != null && o.getClass() != null && o.getClass().getPackage() != null && o.getClass().getPackage().getName().matches("java\\..*");
	}

	private static boolean cannotGoDeeper(final Object o) {
		return o.getClass().getPackage() != null && o.getClass().getPackage().getName().matches("org\\.hibernate\\..*");
	}

	public static <T> boolean isSameJpaEntity(T t1, T t2) throws Exception {
		if (isJpaEntity(t1) && isJpaEntity(t2) && (getId(t1) != null && getId(t2) != null)) {
			if (t1.getClass().equals(t2.getClass()) && getId(t1).equals(getId(t2))) {
				logger.debug("Two jpa entities have same id");
				return true;
			}
		}
		logger.debug("Two jpa entities have different ids");
		return false;
	}

	private static boolean isJpaEntity(Object o) {
		return o.getClass().getAnnotation(javax.persistence.Entity.class) != null;
	}

	private static Object getId(Object o) throws IllegalArgumentException, IllegalAccessException {
		for (Field field : o.getClass().getDeclaredFields()) {
			if (field.getAnnotation(javax.persistence.Id.class) != null) {
				field.setAccessible(true);
				return field.get(o);
			} else if (field.getAnnotation(javax.persistence.EmbeddedId.class) != null) {
				field.setAccessible(true);
				return field.get(o);
			}
		}
		return null;
	}

	public static String getDescription(Object o, String name) {
		Method[] ms = o.getClass().getDeclaredMethods();
		try {
			RmDescription rmDescription = o.getClass().getDeclaredField(name).getAnnotation(RmDescription.class);
			return rmDescription.value();
		} catch (NoSuchFieldException nfe) {
			try {
				RmDescription rmDescription = o.getClass().getDeclaredMethod(name).getAnnotation(RmDescription.class);
				return rmDescription.value();
			} catch (NoSuchMethodException nme) {
				logger.error("NdsBeanUtils: Field or method name {} not found!", name);
			}
			return "";
		}
	}

	public static List<String> getBeanDescriptions(Object o) {
		List<String> descriptions = new ArrayList<>();
		Field[] fields = o.getClass().getDeclaredFields();
		for (Field f : fields) {
			RmDescription rmDescription = f.getAnnotation(RmDescription.class);
			if (rmDescription != null) {
				descriptions.add(rmDescription.value());
			}
		}
		Method[] methods = o.getClass().getDeclaredMethods();
		for (Method m : methods) {
			RmDescription rmDescription = m.getAnnotation(RmDescription.class);
			if (rmDescription != null) {
				descriptions.add(rmDescription.value());
			}
		}
		return descriptions;
	}
}
