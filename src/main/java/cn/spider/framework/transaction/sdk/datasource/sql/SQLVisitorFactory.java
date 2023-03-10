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
package cn.spider.framework.transaction.sdk.datasource.sql;

import cn.spider.framework.transaction.sdk.sqlparser.druid.DruidDelegatingSQLRecognizerFactory;
import cn.spider.framework.transaction.sdk.sqlparser.SQLRecognizer;
import cn.spider.framework.transaction.sdk.sqlparser.SQLRecognizerFactory;

import java.util.List;

/**
 * @author ggndnn
 */
public class SQLVisitorFactory {
    /**
     * SQLRecognizerFactory.
     */
    private final static SQLRecognizerFactory SQL_RECOGNIZER_FACTORY;

    static {
        SQL_RECOGNIZER_FACTORY = new DruidDelegatingSQLRecognizerFactory();
    }

    /**
     * Get sql recognizer.
     *
     * @param sql    the sql
     * @param dbType the db type
     * @return the sql recognizer
     */
    public static List<SQLRecognizer> get(String sql, String dbType) {
        return SQL_RECOGNIZER_FACTORY.create(sql, dbType);
    }


}
