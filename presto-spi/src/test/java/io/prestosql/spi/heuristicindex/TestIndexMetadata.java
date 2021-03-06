/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.prestosql.spi.heuristicindex;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.testng.Assert.assertEquals;

public class TestIndexMetadata
{
    @Test
    public void test()
    {
        Index index = new Index()
        {
            @Override
            public String getId()
            {
                return null;
            }

            @Override
            public void addValues(Object[] values)
            {
                //do nothing
            }

            @Override
            public boolean matches(Object value, Operator operator) throws IllegalArgumentException
            {
                return false;
            }

            @Override
            public boolean supports(Operator operator)
            {
                return false;
            }

            @Override
            public void persist(OutputStream out) throws IOException
            {
                //do nothing
            }

            @Override
            public void load(InputStream in) throws IOException
            {
                //do nothing
            }

            @Override
            public int getExpectedNumOfEntries()
            {
                return 0;
            }

            @Override
            public void setExpectedNumOfEntries(int expectedNumOfEntries)
            {
            }
        };

        String table = "table";
        String column = "column";
        String rootUri = "/tmp";
        String uri = "foo";
        long splitStart = 10;
        long lastUpdated = 20;

        IndexMetadata indexMetadata = new IndexMetadata(
                index,
                table,
                column,
                rootUri,
                uri,
                splitStart,
                lastUpdated);
        assertEquals(indexMetadata.getIndex(), index);
        assertEquals(indexMetadata.getTable(), table);
        assertEquals(indexMetadata.getColumn(), column);
        assertEquals(indexMetadata.getRootUri(), rootUri);
        assertEquals(indexMetadata.getUri(), uri);
        assertEquals(indexMetadata.getSplitStart(), splitStart);
        assertEquals(indexMetadata.getLastUpdated(), lastUpdated);
    }
}
