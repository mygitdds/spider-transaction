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
package cn.spider.framework.transaction.sdk.context;

import cn.spider.framework.transaction.sdk.loader.LoadLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * The type Thread local context core.
 *
 * @author slievrly
 */
@LoadLevel(name = "ThreadLocalContextCore", order = Integer.MIN_VALUE)
public class ThreadLocalContextCore implements ContextCore {

    private ThreadLocal<Map<String, Object>> threadLocal = ThreadLocal.withInitial(HashMap::new);

    @Override
    public Object put(String key, Object value) {
        return threadLocal.get().put(key, value);
    }

    @Override
    public Object get(String key) {
        return threadLocal.get().get(key);
    }

    @Override
    public Object remove(String key) {
        return threadLocal.get().remove(key);
    }

    @Override
    public Map<String, Object> entries() {
        return threadLocal.get();
    }
}
