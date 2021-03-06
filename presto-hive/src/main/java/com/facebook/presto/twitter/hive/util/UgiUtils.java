/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.twitter.hive.util;

import org.apache.hadoop.security.UserGroupInformation;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class to handle creating and caching the UserGroupInformation object.
 */
public class UgiUtils
{
    private UgiUtils() {}

    // Every instance of a UserGroupInformation object for a given user has a unique hashcode, due
    // to the hashCode() impl. If we don't cache the UGI per-user here, there will be a memory leak
    // in the PrestoFileSystemCache.
    private static final Map<String, UserGroupInformation> UGI_CACHE = new ConcurrentHashMap<>();

    public static UserGroupInformation getUgi(String user)
    {
        UserGroupInformation ugi = UGI_CACHE.get(user);

        if (ugi == null) {
            // Configure hadoop to allow presto daemon user to impersonate all presto users
            // See https://hadoop.apache.org/docs/r2.4.1/hadoop-project-dist/hadoop-common/Superusers.html
            try {
                ugi = UserGroupInformation.createProxyUser(user, UserGroupInformation.getLoginUser());
            }
            catch (IOException e) {
                throw new RuntimeException("Could not get login user from UserGroupInformation", e);
            }
            UGI_CACHE.put(user, ugi);
        }

        return ugi;
    }
}
