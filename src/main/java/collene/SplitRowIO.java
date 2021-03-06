/*
 * Copyright 2014 Gary Dusbabek
 *
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

package collene;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class SplitRowIO implements IO {
    private final IO io;
    private final int splits;
    private final String delimiter;
    
    public SplitRowIO(int splits, String delimiter, IO io) {
        this.io = io;
        this.splits = splits;
        this.delimiter = delimiter;
    }
    
    @Override
    public void put(String key, long col, byte[] value) throws IOException {
        io.put(dbKey(key, col % splits), col, value);
    }

    @Override
    public byte[] get(String key, long col) throws IOException {
        return io.get(dbKey(key, col % splits), col);
    }

    @Override
    public int getColSize() {
        return io.getColSize();
    }

    @Override
    public void delete(String key) throws IOException {
        for (long mod = 0; mod < splits; mod++) {
            io.delete(String.format("%s%s%d", key, delimiter, mod));
        }
    }

    @Override
    public Iterable<byte[]> allValues(String key) throws IOException {
        List<byte[]> list = new ArrayList<byte[]>();
        for (long mod = 0; mod < splits; mod++) {
            list.addAll(Utils.asCollection(io.allValues(dbKey(key, mod))));
            if (mod != 0) {
                list.addAll(Utils.asCollection(io.allValues(dbKey(key, -mod))));
            }
        }
        return list;
    }

    @Override
    public void delete(String key, long col) throws IOException {
        io.delete(dbKey(key, col % splits), col);
    }

    @Override
    public boolean hasKey(String key) throws IOException {
        for (long mod = 0; mod < splits; mod++) {
            if (io.hasKey(dbKey(key, mod))) {
                return true;
            }
        }
        return false;
    }
    
    private String dbKey(String key, long mod) {
        return String.format("%s%s%d", key, delimiter, mod);
    }
}
