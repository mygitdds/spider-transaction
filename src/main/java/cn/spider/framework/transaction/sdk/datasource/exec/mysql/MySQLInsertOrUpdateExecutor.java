/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package cn.spider.framework.transaction.sdk.datasource.exec.mysql;

import cn.spider.framework.transaction.sdk.datasource.sql.struct.*;
import cn.spider.framework.transaction.sdk.loader.LoadLevel;
import cn.spider.framework.transaction.sdk.loader.Scope;
import cn.spider.framework.transaction.sdk.util.CollectionUtils;
import cn.spider.framework.transaction.sdk.util.IOUtil;
import cn.spider.framework.transaction.sdk.util.StringUtils;
import cn.spider.framework.transaction.sdk.core.exception.NotSupportYetException;
import cn.spider.framework.transaction.sdk.core.exception.ShouldNeverHappenException;
import cn.spider.framework.transaction.sdk.datasource.ConnectionProxy;
import cn.spider.framework.transaction.sdk.datasource.PreparedStatementProxy;
import cn.spider.framework.transaction.sdk.datasource.StatementProxy;
import cn.spider.framework.transaction.sdk.datasource.exec.StatementCallback;
import cn.spider.framework.transaction.sdk.datasource.undo.SQLUndoLog;
import cn.spider.framework.transaction.sdk.datasource.util.JdbcConstants;
import cn.spider.framework.transaction.sdk.sqlparser.SQLInsertRecognizer;
import cn.spider.framework.transaction.sdk.sqlparser.SQLRecognizer;
import cn.spider.framework.transaction.sdk.sqlparser.SQLType;
import cn.spider.framework.transaction.sdk.sqlparser.struct.Defaultable;
import cn.spider.framework.transaction.sdk.sqlparser.struct.Null;
import com.google.common.base.Joiner;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * @author: yangyicong
 */
@LoadLevel(name = JdbcConstants.MYSQL, scope = Scope.PROTOTYPE)
public class MySQLInsertOrUpdateExecutor extends MySQLInsertExecutor implements Defaultable {


    private static final String COLUMN_SEPARATOR = "|";

    /**
     * is updated or not
     */
    private boolean isUpdateFlag = false;

    public String getSelectSQL() {
        return selectSQL;
    }

    /**
     * before image sql and after image sql,condition is unique index
     */
    private String selectSQL;

    public ArrayList<List<Object>> getParamAppenderList() {
        return paramAppenderList;
    }

    /**
     * the params of selectSQL, value is the unique index
     */
    private ArrayList<List<Object>> paramAppenderList;

    public MySQLInsertOrUpdateExecutor(StatementProxy statementProxy, StatementCallback statementCallback, SQLRecognizer sqlRecognizer) {
        super(statementProxy, statementCallback, sqlRecognizer);
    }

    /**
     * Execute auto commit false t.
     *
     * @param args the args
     * @return the t
     * @throws Exception the exception
     */
    @Override
    protected Object executeAutoCommitFalse(Object[] args) throws Exception {
        if (!JdbcConstants.MYSQL.equalsIgnoreCase(getDbType()) && getTableMeta().getPrimaryKeyOnlyName().size() > 1) {
            throw new NotSupportYetException("multi pk only support mysql!");
        }
        TableRecords beforeImage = beforeImage();
        if (CollectionUtils.isNotEmpty(beforeImage.getRows())) {
            isUpdateFlag = true;
        } else {
            beforeImage = TableRecords.empty(getTableMeta());
        }
        Object result = statementCallback.execute(statementProxy.getTargetStatement(), args);
        TableRecords afterImage = afterImage(beforeImage);
        prepareUndoLogAll(beforeImage, afterImage);
        return result;
    }

    /**
     * prepare undo log.
     *
     * @param beforeImage the before image
     * @param afterImage  the after image
     */
    protected void prepareUndoLogAll(TableRecords beforeImage, TableRecords afterImage) {
        if (beforeImage.getRows().isEmpty() && afterImage.getRows().isEmpty()) {
            return;
        }
        ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
        TableRecords lockKeyRecords = afterImage;
        String lockKeys = buildLockKey(lockKeyRecords);
        connectionProxy.appendLockKey(lockKeys);
        buildUndoItemAll(connectionProxy, beforeImage, afterImage);
    }

    /**
     * build a SQLUndoLog
     *
     * @param beforeImage the before image
     * @param afterImage  the after image
     */
    protected void buildUndoItemAll(ConnectionProxy connectionProxy, TableRecords beforeImage, TableRecords afterImage) {
        if (!isUpdateFlag) {
            SQLUndoLog sqlUndoLog = buildUndoItem(SQLType.INSERT, TableRecords.empty(getTableMeta()), afterImage);
            connectionProxy.appendUndoLog(sqlUndoLog);
            return;
        }
        List<Row> beforeImageRows = beforeImage.getRows();
        List<String> befrePrimaryValues = new ArrayList<>();
        for (Row r : beforeImageRows) {
            String primaryValue = "";
            for (Field f: r.primaryKeys()) {
                primaryValue = primaryValue + f.getValue() + COLUMN_SEPARATOR;
            }
            befrePrimaryValues.add(primaryValue);
        }
        List<Row> insertRows = new ArrayList<>();
        List<Row> updateRows = new ArrayList<>();
        List<Row> afterImageRows = afterImage.getRows();
        for (Row r : afterImageRows) {
            String primaryValue = "";
            for (Field f: r.primaryKeys()) {
                primaryValue = primaryValue + f.getValue()  + COLUMN_SEPARATOR;
            }
            if (befrePrimaryValues.contains(primaryValue)) {
                updateRows.add(r);
            } else {
                insertRows.add(r);
            }
        }
        if (CollectionUtils.isNotEmpty(updateRows)) {
            TableRecords partAfterImage = new TableRecords(afterImage.getTableMeta());
            partAfterImage.setTableName(afterImage.getTableName());
            partAfterImage.setRows(updateRows);
            if (beforeImage.getRows().size() != partAfterImage.getRows().size()) {
                throw new ShouldNeverHappenException("Before image size is not equaled to after image size, probably because you updated the primary keys.");
            }
            connectionProxy.appendUndoLog(buildUndoItem(SQLType.UPDATE, beforeImage, partAfterImage));
        }
        if (CollectionUtils.isNotEmpty(insertRows)) {
            TableRecords partAfterImage = new TableRecords(afterImage.getTableMeta());
            partAfterImage.setTableName(afterImage.getTableName());
            partAfterImage.setRows(insertRows);
            connectionProxy.appendUndoLog(buildUndoItem(SQLType.INSERT, TableRecords.empty(getTableMeta()), partAfterImage));
        }
    }

    /**
     * build a SQLUndoLog
     *
     * @param sqlType
     * @param beforeImage
     * @param afterImage
     * @return sqlUndoLog the sql undo log
     */
    protected SQLUndoLog buildUndoItem(SQLType sqlType, TableRecords beforeImage, TableRecords afterImage) {
        String tableName = sqlRecognizer.getTableName();
        SQLUndoLog sqlUndoLog = new SQLUndoLog();
        sqlUndoLog.setSqlType(sqlType);
        sqlUndoLog.setTableName(tableName);
        sqlUndoLog.setBeforeImage(beforeImage);
        sqlUndoLog.setAfterImage(afterImage);
        return sqlUndoLog;
    }


    @Override
    protected TableRecords afterImage(TableRecords beforeImage) throws SQLException {
        TableMeta tmeta = getTableMeta();
        List<Row> rows = beforeImage.getRows();
        Map<String, ArrayList<Object>> primaryValueMap = new HashMap<>();
        rows.forEach(m -> {
            List<Field> fields = m.primaryKeys();
            fields.forEach(f -> {
                ArrayList<Object> values = primaryValueMap.computeIfAbsent(f.getName(), v -> new ArrayList<>());
                values.add(f.getValue());
            });
        });

        StringBuilder afterImageSql = new StringBuilder(selectSQL);
        for (int i = 0; i < rows.size(); i++) {
            int finalI = i;
            List<String> wherePrimaryList = new ArrayList<>();
            primaryValueMap.forEach((k, v) -> {
                wherePrimaryList.add(k + " = " +  primaryValueMap.get(k).get(finalI) + " ");
            });
            afterImageSql.append(" OR (").append(Joiner.on(" and ").join(wherePrimaryList)).append(") ");
        }
        return buildTableRecords2(tmeta, afterImageSql.toString(), paramAppenderList);
    }

    @Override
    public TableRecords beforeImage() throws SQLException {
        TableMeta tmeta = getTableMeta();
        //after image sql the same of before image
        if (StringUtils.isBlank(selectSQL)) {
            paramAppenderList = new ArrayList<>();
            selectSQL = buildImageSQL(tmeta);
        }
        return buildTableRecords2(tmeta, selectSQL, paramAppenderList);
    }

    /**
     * build TableRecords
     *
     * @param tableMeta
     * @param selectSQL
     * @param paramAppenderList
     * @return the table records
     * @throws SQLException
     */
    public TableRecords buildTableRecords2(TableMeta tableMeta, String selectSQL, ArrayList<List<Object>> paramAppenderList) throws SQLException {
        ResultSet rs = null;
        try (PreparedStatement ps = statementProxy.getConnection().prepareStatement(selectSQL + " FOR UPDATE")) {
            if (CollectionUtils.isNotEmpty(paramAppenderList)) {
                for (int i = 0, ts = paramAppenderList.size(); i < ts; i++) {
                    List<Object> paramAppender = paramAppenderList.get(i);
                    for (int j = 0, ds = paramAppender.size(); j < ds; j++) {
                        ps.setObject(i * ds + j + 1, "NULL".equals(paramAppender.get(j).toString()) ? null : paramAppender.get(j));
                    }
                }
            }
            rs = ps.executeQuery();
            return TableRecords.buildRecords(tableMeta, rs);
        } finally {
            IOUtil.close(rs);
        }
    }

    /**
     * build image sql
     *
     * @param tableMeta
     * @return image sql
     */
    public String buildImageSQL(TableMeta tableMeta) {
        if (CollectionUtils.isEmpty(paramAppenderList)) {
            paramAppenderList = new ArrayList<>();
        }
        SQLInsertRecognizer recognizer = (SQLInsertRecognizer) sqlRecognizer;
        int insertNum = recognizer.getInsertParamsValue().size();
        Map<String, ArrayList<Object>> imageParamperterMap = buildImageParamperters(recognizer);
        StringBuilder prefix = new StringBuilder("SELECT * ");
        StringBuilder suffix = new StringBuilder(" FROM ").append(getFromTableInSQL());
        boolean[] isContainWhere = {false};
        for (int i = 0; i < insertNum; i++) {
            int finalI = i;
            List<Object> paramAppenderTempList = new ArrayList<>();
            tableMeta.getAllIndexes().forEach((k, v) -> {
                if (!v.isNonUnique()) {
                    boolean columnIsNull = true;
                    List<String> uniqueList = new ArrayList<>();
                    for (ColumnMeta m : v.getValues()) {
                        String columnName = m.getColumnName();
                        if (imageParamperterMap.get(columnName) == null && m.getColumnDef() != null) {
                            uniqueList.add(columnName + " = DEFAULT(" + columnName + ") ");
                            columnIsNull = false;
                            continue;
                        }
                        if ((imageParamperterMap.get(columnName) == null && m.getColumnDef() == null) || imageParamperterMap.get(columnName).get(finalI) == null || imageParamperterMap.get(columnName).get(finalI) instanceof Null) {
                            if (!"PRIMARY".equalsIgnoreCase(k)) {
                                columnIsNull = false;
                                uniqueList.add(columnName + " is ? ");
                                paramAppenderTempList.add("NULL");
                                continue;
                            }
                            break;
                        }
                        columnIsNull = false;
                        uniqueList.add(columnName + " = ? ");
                        paramAppenderTempList.add(imageParamperterMap.get(columnName).get(finalI));
                    }
                    if (!columnIsNull) {
                        if (isContainWhere[0]) {
                            suffix.append(" OR (").append(Joiner.on(" and ").join(uniqueList)).append(") ");
                        } else {
                            suffix.append(" WHERE (").append(Joiner.on(" and ").join(uniqueList)).append(") ");
                            isContainWhere[0] = true;
                        }
                    }
                }
            });
            paramAppenderList.add(paramAppenderTempList);
        }
        StringJoiner selectSQLJoin = new StringJoiner(", ", prefix.toString(), suffix.toString());
        return selectSQLJoin.toString();
    }

    /**
     * build sql params
     *
     * @param recognizer
     * @return map, key is column, value is paramperter
     */
    @SuppressWarnings("lgtm[java/dereferenced-value-may-be-null]")
    public Map<String, ArrayList<Object>> buildImageParamperters(SQLInsertRecognizer recognizer) {
        List<String> duplicateKeyUpdateCloms = recognizer.getDuplicateKeyUpdate();
        if (CollectionUtils.isNotEmpty(duplicateKeyUpdateCloms)) {
            getTableMeta().getAllIndexes().forEach((k, v) -> {
                if ("PRIMARY".equalsIgnoreCase(k)) {
                    for (ColumnMeta m : v.getValues()) {
                        if (duplicateKeyUpdateCloms.contains(m.getColumnName())) {
                            throw new ShouldNeverHappenException("update pk value is not supported!");
                        }
                    }
                }
            });
        }
        Map<String, ArrayList<Object>> imageParamperterMap = new HashMap<>();
        Map<Integer, ArrayList<Object>> parameters = ((PreparedStatementProxy) statementProxy).getParameters();
        //  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        List<String> insertParamsList = recognizer.getInsertParamsValue();
        List<String> insertColumns = recognizer.getInsertColumns();
        int paramsindex = 1;
        for (String insertParams : insertParamsList) {
            String[] insertParamsArray = insertParams.split(",");
            for (int i = 0; i < insertColumns.size(); i++) {
                String m = insertColumns.get(i);
                String params = insertParamsArray[i];
                ArrayList<Object> imageListTemp = imageParamperterMap.computeIfAbsent(m, k -> new ArrayList<>());
                if ("?".equals(params.trim())) {
                    ArrayList<Object> objects = parameters.get(paramsindex);
                    imageListTemp.addAll(objects);
                    paramsindex++;
                } else if (params instanceof String) {
                    // params is characterstring constant
                    if ((params.trim().startsWith("'") && params.trim().endsWith("'")) || params.trim().startsWith("\"") && params.trim().endsWith("\"")) {
                        params = params.trim();
                        params = params.substring(1, params.length() - 1);
                    }
                    imageListTemp.add(params);
                } else {
                    imageListTemp.add(params);
                }
                imageParamperterMap.put(m, imageListTemp);
            }
        }
        return imageParamperterMap;
    }

}
