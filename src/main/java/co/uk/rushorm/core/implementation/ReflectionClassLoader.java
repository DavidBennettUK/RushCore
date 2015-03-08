package co.uk.rushorm.core.implementation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.uk.rushorm.core.AnnotationCache;
import co.uk.rushorm.core.Rush;
import co.uk.rushorm.core.RushClassLoader;
import co.uk.rushorm.core.RushColumns;
import co.uk.rushorm.core.RushMetaData;
import co.uk.rushorm.core.RushStatementRunner;

/**
 * Created by Stuart on 14/12/14.
 */
public class ReflectionClassLoader implements RushClassLoader {

    private class Join {
        private final Rush parent;
        private final String tableName;
        private final Field field;
        private Join(Rush parentId, String tableName, Field field) {
            this.parent = parentId;
            this.tableName = tableName;
            this.field = field;
        }
    }

    private interface AttachChild<T extends Rush> {
        public void attach(T object, List<String> values) throws IllegalAccessException;
    }

    @Override
    public <T extends Rush> List<T> loadClasses(Class<T> clazz, RushColumns rushColumns, Map<Class, AnnotationCache> annotationCache, RushStatementRunner.ValuesCallback valuesCallback, LoadCallback callback) {
        return loadClasses(clazz, rushColumns, annotationCache, valuesCallback, callback, new HashMap<Class, Map<String, T>>(), null);
    }

    public <T extends Rush> List<T> loadClasses(Class<T> clazz, RushColumns rushColumns, Map<Class, AnnotationCache> annotationCache, RushStatementRunner.ValuesCallback valuesCallback, LoadCallback callback, Map<Class, Map<String, T>> loadedClasses, AttachChild<T> attachChild) {
        try {

            Map<Class, List<Join>> joins = new HashMap<>();
            Map<Class, List<String>> joinTables = new HashMap<>();

            List<T> results = new ArrayList<>();
            while(valuesCallback.hasNext()) {
                List<String> valuesList = valuesCallback.next();
                T object = loadClass(clazz, rushColumns, annotationCache, valuesList, joins, joinTables, loadedClasses, callback);
                results.add(object);
                if(attachChild != null) {
                    attachChild.attach(object, valuesList);
                }
            }
            valuesCallback.close();

            for (Map.Entry<Class, List<Join>> entry : joins.entrySet()) {
                addChildrenToList(entry.getKey(), rushColumns, annotationCache, entry.getValue(), joinTables.get(entry.getKey()), loadedClasses, callback);
            }

            return results;
        } catch (InstantiationException | ClassNotFoundException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private <T extends Rush> T loadClass(Class<T> clazz, RushColumns rushColumns, Map<Class, AnnotationCache> annotationCache, List<String> values, Map<Class, List<Join>> joins, Map<Class, List<String>> joinTables, Map<Class, Map<String, T>> loadedClasses, LoadCallback callback) throws IllegalAccessException, InstantiationException, ClassNotFoundException {

        RushMetaData rushMetaData = new RushMetaData(values.get(0), Long.parseLong(values.get(1)), Long.parseLong(values.get(2)), Long.parseLong(values.get(3)));

        if(!loadedClasses.containsKey(clazz)) {
            loadedClasses.put(clazz, new HashMap<String, T>());
        }
        if(loadedClasses.get(clazz).containsKey(rushMetaData.getId())) {
            return loadedClasses.get(clazz).get(rushMetaData.getId());
        }
        T object = clazz.newInstance();

        loadedClasses.get(clazz).put(rushMetaData.getId(), object);
        callback.didLoadObject(object, rushMetaData);

        List<Field> fields = new ArrayList<>();
        ReflectionUtils.getAllFields(fields, clazz);

        int counter = 4; /* Skip rush_id, rush_created, rush_updated and rush_version */
        for (Field field : fields) {
            field.setAccessible(true);
            if (!annotationCache.get(clazz).getFieldToIgnore().contains(field.getName())) {
                if (!loadJoinField(object, rushColumns, annotationCache, field, joins, joinTables)) {
                    if(rushColumns.supportsField(field)) {
                        String value = values.get(counter);
                        if(value != null && !value.equals("null")) {
                            rushColumns.setField(object, field, value);
                        }
                        counter++;
                    }
                }
            }
        }
        return object;
    }

    private <T extends Rush> boolean loadJoinField(T object, RushColumns rushColumns, Map<Class, AnnotationCache> annotationCache, Field field, Map<Class, List<Join>> joins,  Map<Class, List<String>> joinTables) {
        Class clazz = null;
        if (Rush.class.isAssignableFrom(field.getType())) {
            clazz = field.getType();
        } else if(annotationCache.get(object.getClass()).getListsFields().containsKey(field.getName())){
            clazz = annotationCache.get(object.getClass()).getListsFields().get(field.getName());
        }
        if(clazz != null) {
            if(!joins.containsKey(clazz)) {
                joins.put(clazz, new ArrayList<Join>());
                joinTables.put(clazz, new ArrayList<String>());
            }
            String tableName = ReflectionUtils.joinTableNameForClass(object.getClass(), clazz, field, annotationCache);
            joins.get(clazz).add(new Join(object, tableName, field));
            if(!joinTables.get(clazz).contains(tableName)) {
                joinTables.get(clazz).add(tableName);
            }
            return true;
        }
        return false;
    }

    private static final String SELECT_CHILDREN = "SELECT * from %s \n" +
            "%s" +
            "WHERE %s;";

    private <T extends Rush> void addChildrenToList(final Class<T> clazz, final RushColumns rushColumns, final Map<Class, AnnotationCache> annotationCache, final List<Join> joins, final List<String> tableNames, final Map<Class, Map<String, T>> loadedClasses, final LoadCallback callback) {

        final String tableName = ReflectionUtils.tableNameForClass(clazz, annotationCache);
        final Map<Integer, String> tableMap = new HashMap<>();
        final Map<String, Map<String, Join>> parentMap = new HashMap<>();
        final String joinsString = joinSection(tableName, tableNames, tableMap, parentMap);
        final StringBuilder columnsString = new StringBuilder();

        doLoop(joins.size(), 250, new LoopCallBack() {
            @Override
            public void start() {
                columnsString.delete(0, columnsString.length());
            }

            @Override
            public void actionAtIndex(int index) {
                Join join = joins.get(index);
                parentMap.get(join.tableName).put(join.parent.getId(), join);
                columnsString.append(join.tableName)
                        .append(".parent = '")
                        .append(join.parent.getId())
                        .append("'");
            }

            @Override
            public void join() {
                columnsString.append(" OR ");
            }

            @Override
            public void doAction(int at) {
                String sql = String.format(SELECT_CHILDREN, tableName, joinsString, columnsString.toString());
                RushStatementRunner.ValuesCallback values = callback.runStatement(sql);
                loadClasses(clazz, rushColumns, annotationCache, values, callback, loadedClasses, new AttachChild<T>() {
                    @Override
                    public void attach(T object, List<String> values) throws IllegalAccessException {
                        int i = values.size() - 2;
                        int offset = 1;
                        while(values.get(i) == null) {
                            i -= 3;
                            offset += 3;
                        }
                        String parentId = values.get(i);
                        String tableName = tableMap.get(offset);
                        Join join = parentMap.get(tableName).get(parentId);
                        Rush parent = join.parent;
                        if(Rush.class.isAssignableFrom(join.field.getType())) {
                                join.field.set(parent, object);
                        } else {
                            List<Rush> children = (List<Rush>)join.field.get(parent);
                            if(children == null) {
                                children = new ArrayList<>();
                                join.field.set(parent, children);
                            }
                            children.add(object);
                        }
                    }
                });
            }
        });
    }

    private String joinSection(String tableName, List<String> tableNames, Map<Integer, String> tableMap, Map<String, Map<String, Join>> parentMap) {
        StringBuilder stringBuilder = new StringBuilder();
        int counter = (tableNames.size() * 3) - 2;
        for(String joinTableName : tableNames) {
            tableMap.put(counter, joinTableName);
            stringBuilder.append("LEFT JOIN ")
                    .append(joinTableName)
                    .append(" ON ")
                    .append(tableName)
                    .append(".").append(RushSqlUtils.RUSH_ID)
                    .append(" = ")
                    .append(joinTableName)
                    .append(".child \n");
            counter -= 3;
            parentMap.put(joinTableName, new HashMap<String, Join>());
        }
        return stringBuilder.toString();
    }

    private interface LoopCallBack {
        public void start();
        public void actionAtIndex(int index);
        public void join();
        public void doAction(int at);
    }

    private void doLoop(int max, int interval, LoopCallBack callBack) {
        callBack.start();
        for (int i = 0; i < max; i ++) {
            callBack.actionAtIndex(i);
            if(i > 0 && i % interval == 0) {
                callBack.doAction(i);
                callBack.start();
            } else if(i < max - 1) {
                callBack.join();
            }
        }
        if(max == 1 || (max - 1) % interval != 0) {
            callBack.doAction(max - 1);
        }
    }
}
