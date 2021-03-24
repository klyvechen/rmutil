package rm.project.util;

import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;


public class RmCompareResult {

    private Map<String, RmCompareResult> properties;

    @Builder
    public RmCompareResult(Object o1, Object o2, boolean same) {
        this.o1 = o1;
        this.o2 = o2;
        this.same = same;
        properties = new HashMap<>();
    }

    @Getter
    @Setter
    private Object o1, o2;

    private Class clazz;

    @Getter
    @Setter
    private boolean same;

    private String name;

    public void addDiff(String name, Object o1, Object o2) {
        Object o = o1 != null ? o1 : o2 != null ? o2 : null;
        if (o == null)
            return;
        if (properties == null) {
            System.out.println("Properties is null");
        }
        properties.put(name, RmCompareResult.builder().o1(o1 != null ? o1 : null).o2(o2 != null ? o2 : null).build());
        this.setSame(false);
    }

    public void addCompareResult(String name, RmCompareResult compareResult) {
        properties.put(name, compareResult);
        this.setSame(false);
    }

    public RmCompareResult getDiff(String name) {
        return properties.get(name);
    }

    public List<String> getDiffNames() {
        return new ArrayList<>(properties.keySet());
    }
}
