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
package cn.spider.framework.transaction.sdk.sqlparser.druid;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.visitor.SQLASTVisitor;
import cn.spider.framework.transaction.sdk.sqlparser.SQLParsingException;
import cn.spider.framework.transaction.sdk.sqlparser.SQLRecognizer;


/**
 * The type Base recognizer.
 *
 * @author DDS
 */
public abstract class BaseRecognizer implements SQLRecognizer {

    /**
     * The type V marker.
     */
    public static class VMarker {
        @Override
        public String toString() {
            return "?";
        }

    }

    /**
     * The Original sql.
     */
    protected String originalSQL;

    /**
     * Instantiates a new Base recognizer.
     *
     * @param originalSQL the original sql
     */
    public BaseRecognizer(String originalSQL) {
        this.originalSQL = originalSQL;

    }

    public void executeVisit(SQLExpr where, SQLASTVisitor visitor) {
        if (where instanceof SQLBinaryOpExpr) {
            visitor.visit((SQLBinaryOpExpr) where);
        } else if (where instanceof SQLInListExpr) {
            visitor.visit((SQLInListExpr) where);
        } else if (where instanceof SQLBetweenExpr) {
            visitor.visit((SQLBetweenExpr) where);
        } else if (where instanceof SQLExistsExpr) {
            visitor.visit((SQLExistsExpr) where);
        } else {
            throw new IllegalArgumentException("unexpected WHERE expr: " + where.getClass().getSimpleName());
        }
    }

    protected void wrapSQLParsingException(SQLExpr expr) {
        String errorMsg;
        try {
            errorMsg =
                new StringBuilder("Unknown SQLExpr: ").append(expr.getClass()).append(" ").append(expr).toString();
        } catch (Exception e) {
            // druid 1.2.6 SQLObjectImpl#toString exist NPE https://github.com/alibaba/druid/issues/4290
            throw new SQLParsingException("Unknown SQLExpr: " + e.getMessage(), e);
        }
        throw new SQLParsingException(errorMsg);
    }

    public void executeLimit(SQLLimit sqlLimit, SQLASTVisitor visitor) {
        visitor.visit(sqlLimit);
    }

    public void executeOrderBy(SQLOrderBy sqlOrderBy,SQLASTVisitor visitor) {
        visitor.visit(sqlOrderBy);
    }

    @Override
    public String getOriginalSQL() {
        return originalSQL;
    }
}
