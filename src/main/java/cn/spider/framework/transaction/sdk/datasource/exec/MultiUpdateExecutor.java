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
package cn.spider.framework.transaction.sdk.datasource.exec;

import cn.spider.framework.transaction.sdk.datasource.sql.struct.TableMeta;
import cn.spider.framework.transaction.sdk.datasource.sql.struct.TableRecords;
import cn.spider.framework.transaction.sdk.datasource.util.ColumnUtils;
import cn.spider.framework.transaction.sdk.util.IOUtil;
import cn.spider.framework.transaction.sdk.util.StringUtils;
import cn.spider.framework.transaction.sdk.core.exception.NotSupportYetException;
import cn.spider.framework.transaction.sdk.datasource.SqlGenerateUtils;
import cn.spider.framework.transaction.sdk.datasource.StatementProxy;
import cn.spider.framework.transaction.sdk.sqlparser.SQLRecognizer;
import cn.spider.framework.transaction.sdk.sqlparser.SQLUpdateRecognizer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * The type MultiSql executor.
 *
 * @param <T> the type parameter
 * @param <S> the type parameter
 * @author wangwei-ying
 */
public class MultiUpdateExecutor<T, S extends Statement> extends AbstractDMLBaseExecutor<T, S> {

   // private static final Configuration CONFIG = ConfigurationFactory.getInstance();

    private static final boolean ONLY_CARE_UPDATE_COLUMNS = true;

    /**
     * Instantiates a new Multi update executor.
     *
     * @param statementProxy    the statement proxy
     * @param statementCallback the statement callback
     * @param sqlRecognizers    the sql recognizers
     */
    public MultiUpdateExecutor(StatementProxy<S> statementProxy, StatementCallback<T, S> statementCallback, List<SQLRecognizer> sqlRecognizers) {
        super(statementProxy, statementCallback, sqlRecognizers);
    }

    @Override
    protected TableRecords beforeImage() throws SQLException {
        if (sqlRecognizers.size() == 1) {
            UpdateExecutor executor = new UpdateExecutor<>(statementProxy, statementCallback, sqlRecognizers.get(0));
            return executor.beforeImage();
        }
        final TableMeta tmeta = getTableMeta(sqlRecognizers.get(0).getTableName());

        final ArrayList<List<Object>> paramAppenderList = new ArrayList<>();
        Set<String> updateColumnsSet = new HashSet<>();
        StringBuilder whereCondition = new StringBuilder();
        boolean noWhereCondition = false;
        for (SQLRecognizer recognizer : sqlRecognizers) {
            sqlRecognizer = recognizer;
            SQLUpdateRecognizer sqlUpdateRecognizer = (SQLUpdateRecognizer) recognizer;

            if (StringUtils.isNotBlank(sqlUpdateRecognizer.getLimitCondition())) {
                throw new NotSupportYetException("Multi update SQL with limit condition is not support yet !");
            }
            if (StringUtils.isNotBlank(sqlUpdateRecognizer.getOrderByCondition())) {
                throw new NotSupportYetException("Multi update SQL with orderBy condition is not support yet !");
            }

            List<String> updateColumns = sqlUpdateRecognizer.getUpdateColumns();
            updateColumnsSet.addAll(updateColumns);
            if (noWhereCondition) {
                continue;
            }
            String whereConditionStr = buildWhereCondition(sqlUpdateRecognizer, paramAppenderList);
            if (StringUtils.isBlank(whereConditionStr)) {
                noWhereCondition = true;
            } else {
                if (whereCondition.length() > 0) {
                    whereCondition.append(" OR ");
                }
                whereCondition.append(whereConditionStr);
            }
        }
        StringBuilder prefix = new StringBuilder("SELECT ");
        final StringBuilder suffix = new StringBuilder(" FROM ").append(getFromTableInSQL());
        if (noWhereCondition) {
            //select all rows
            paramAppenderList.clear();
        } else {
            suffix.append(" WHERE ").append(whereCondition);
        }
        suffix.append(" FOR UPDATE");
        final StringJoiner selectSQLAppender = new StringJoiner(", ", prefix, suffix.toString());
        if (ONLY_CARE_UPDATE_COLUMNS) {
            if (!containsPK(new ArrayList<>(updateColumnsSet))) {
                selectSQLAppender.add(getColumnNamesInSQL(tmeta.getEscapePkNameList(getDbType())));
            }
            for (String updateCol : updateColumnsSet) {
                selectSQLAppender.add(updateCol);
            }
        } else {
            for (String columnName : tmeta.getAllColumns().keySet()) {
                selectSQLAppender.add(ColumnUtils.addEscape(columnName, getDbType()));
            }
        }
        return buildTableRecords(tmeta, selectSQLAppender.toString(), paramAppenderList);
    }

    @Override
    protected TableRecords afterImage(TableRecords beforeImage) throws SQLException {
        if (sqlRecognizers.size() == 1) {
            UpdateExecutor executor = new UpdateExecutor<>(statementProxy, statementCallback, sqlRecognizers.get(0));
            return executor.afterImage(beforeImage);
        }
        if (beforeImage == null || beforeImage.size() == 0) {
            return TableRecords.empty(getTableMeta(sqlRecognizers.get(0).getTableName()));
        }
        TableMeta tmeta = getTableMeta(sqlRecognizers.get(0).getTableName());
        String selectSQL = buildAfterImageSQL(tmeta, beforeImage);
        ResultSet rs = null;
        try (PreparedStatement pst = statementProxy.getConnection().prepareStatement(selectSQL);) {
            SqlGenerateUtils.setParamForPk(beforeImage.pkRows(), getTableMeta().getPrimaryKeyOnlyName(), pst);
            rs = pst.executeQuery();
            return TableRecords.buildRecords(tmeta, rs);
        } finally {
            IOUtil.close(rs);
        }
    }

    private String buildAfterImageSQL(TableMeta tableMeta, TableRecords beforeImage) throws SQLException {

        Set<String> updateColumnsSet = new HashSet<>();
        for (SQLRecognizer recognizer : sqlRecognizers) {
            sqlRecognizer = recognizer;
            SQLUpdateRecognizer sqlUpdateRecognizer = (SQLUpdateRecognizer) sqlRecognizer;
            updateColumnsSet.addAll(sqlUpdateRecognizer.getUpdateColumns());
        }
        StringBuilder prefix = new StringBuilder("SELECT ");
        String suffix = " FROM " + getFromTableInSQL() + " WHERE " + SqlGenerateUtils.buildWhereConditionByPKs(tableMeta.getPrimaryKeyOnlyName(), beforeImage.pkRows().size(), getDbType());
        StringJoiner selectSQLJoiner = new StringJoiner(", ", prefix.toString(), suffix);
        if (ONLY_CARE_UPDATE_COLUMNS) {
            if (!containsPK(new ArrayList<>(updateColumnsSet))) {
                selectSQLJoiner.add(getColumnNamesInSQL(tableMeta.getEscapePkNameList(getDbType())));
            }
            for (String updateCol : updateColumnsSet) {
                selectSQLJoiner.add(updateCol);
            }
        } else {
            for (String columnName : tableMeta.getAllColumns().keySet()) {
                selectSQLJoiner.add(ColumnUtils.addEscape(columnName, getDbType()));
            }
        }
        return selectSQLJoiner.toString();
    }
}
