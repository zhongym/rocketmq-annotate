/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.List;

import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.store.MappedFile;

/**
 * 索引文件结构
 * <p>
 * |----索引头-----| ------ 50w个hash槽 -----| ----2000w索引条目-------|
 * 索引头结构：IndexHeader
 * hash槽： int值，保存 索引条目的序号（1->20000w）
 * 索引条目结构：keyHash （key的hashcode） + phyOffset（消息对应的物理偏移值） + timeDiff （该消息存储时间与第一条消息的时间戳的差值） + prelndexNo (该条目的前一条Index索引， 出现hash冲突时，构建的链表结)
 */
public class IndexFile {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    private static int hashSlotSize = 4;
    private static int indexSize = 20;
    private static int invalidIndex = 0;
    private final int hashSlotNum;
    //索引条目总数
    private final int indexNum;
    private final MappedFile mappedFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private final IndexHeader indexHeader;

    public IndexFile(final String fileName, final int hashSlotNum, final int indexNum,
                     final long endPhyOffset, final long endTimestamp) throws IOException {
        int fileTotalSize =
                IndexHeader.INDEX_HEADER_SIZE + (hashSlotNum * hashSlotSize) + (indexNum * indexSize);
        this.mappedFile = new MappedFile(fileName, fileTotalSize);
        this.fileChannel = this.mappedFile.getFileChannel();
        this.mappedByteBuffer = this.mappedFile.getMappedByteBuffer();
        this.hashSlotNum = hashSlotNum;
        this.indexNum = indexNum;

        ByteBuffer byteBuffer = this.mappedByteBuffer.slice();
        this.indexHeader = new IndexHeader(byteBuffer);

        if (endPhyOffset > 0) {
            this.indexHeader.setBeginPhyOffset(endPhyOffset);
            this.indexHeader.setEndPhyOffset(endPhyOffset);
        }

        if (endTimestamp > 0) {
            this.indexHeader.setBeginTimestamp(endTimestamp);
            this.indexHeader.setEndTimestamp(endTimestamp);
        }
    }

    public String getFileName() {
        return this.mappedFile.getFileName();
    }

    public void load() {
        this.indexHeader.load();
    }

    public void flush() {
        long beginTime = System.currentTimeMillis();
        if (this.mappedFile.hold()) {
            this.indexHeader.updateByteBuffer();
            this.mappedByteBuffer.force();
            this.mappedFile.release();
            log.info("flush index file elapsed time(ms) " + (System.currentTimeMillis() - beginTime));
        }
    }

    public boolean isWriteFull() {
        return this.indexHeader.getIndexCount() >= this.indexNum;
    }

    public boolean destroy(final long intervalForcibly) {
        return this.mappedFile.destroy(intervalForcibly);
    }

    /**
     * 保存索引
     *
     * @param key            消息索引
     * @param phyOffset      消息物理偏移量
     * @param storeTimestamp 消息存储时间
     * @return
     */
    public boolean putKey(final String key, final long phyOffset, final long storeTimestamp) {
        if (isWriteFull()) {
            log.warn("Over index file capacity: index count = " + this.indexHeader.getIndexCount()
                    + "; index max num = " + this.indexNum);
            return false;
        }

        //计算出key hash值
        int keyHash = indexKeyHashMethod(key);
        //计算出槽位
        int slotPos = keyHash % this.hashSlotNum;
        //计算出槽位的位置 = 索引文件的头大小+槽位*每个槽位大小
        int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

        FileLock fileLock = null;

        try {

            // fileLock = this.fileChannel.lock(absSlotPos, hashSlotSize,false);
            //读取 hash 槽中存储的数据， index 的条目位置
            int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
            if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()) {
                slotValue = invalidIndex;
            }

            //计算待存储消息的时间戳与第一条消息时间戳的差值，并转换成秒
            long timeDiff = storeTimestamp - this.indexHeader.getBeginTimestamp();
            timeDiff = timeDiff / 1000;

            if (this.indexHeader.getBeginTimestamp() <= 0) {
                timeDiff = 0;
            } else if (timeDiff > Integer.MAX_VALUE) {
                timeDiff = Integer.MAX_VALUE;
            } else if (timeDiff < 0) {
                timeDiff = 0;
            }

            //计算出索引条目的偏移值 = 头的大小 + 所有hash槽占用大小 + 已经保存的条目占用大小
            int absIndexPos =
                    IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize + this.indexHeader.getIndexCount() * indexSize;

            //索引条目内容：keyHash （key的hashcode） + phyOffset（消息对应的物理偏移值） + timeDiff （该消息存储时间与第一条消息的时间戳的差值） + prelndexNo (该条目的前一条Index索引， 出现hash冲突时，构建的链表结)
            this.mappedByteBuffer.putInt(absIndexPos, keyHash);
            this.mappedByteBuffer.putLong(absIndexPos + 4, phyOffset);
            this.mappedByteBuffer.putInt(absIndexPos + 4 + 8, (int) timeDiff);
            //prelndexNo (该条目的前一条Index索引， 出现hash冲突时，构建的链表结)
            this.mappedByteBuffer.putInt(absIndexPos + 4 + 8 + 4, slotValue);

            //覆盖记录 索引序号
            //这里是 Hash 突链式解决方案的关键实现， Hash 槽中存储的是该 Hash Code 所对应的
            //最新的 Index 条目的序号，新的 Index 条目的最后 4个字节存储该 Hash Code 上一个条目的Index序号
            this.mappedByteBuffer.putInt(absSlotPos, this.indexHeader.getIndexCount());

            //---------更新文件索引头信息-------------

            if (this.indexHeader.getIndexCount() <= 1) {
                //记录最小消息物理偏移量
                this.indexHeader.setBeginPhyOffset(phyOffset);
                //记录最小消息存储时间
                this.indexHeader.setBeginTimestamp(storeTimestamp);
            }
            this.indexHeader.incHashSlotCount();
            //统计已经保存的条目总数
            this.indexHeader.incIndexCount();
            //记录最大消息物理偏移量
            this.indexHeader.setEndPhyOffset(phyOffset);
            //记录最大消息存储时间
            this.indexHeader.setEndTimestamp(storeTimestamp);

            return true;
        } catch (Exception e) {
            log.error("putKey exception, Key: " + key + " KeyHashCode: " + key.hashCode(), e);
        } finally {
            if (fileLock != null) {
                try {
                    fileLock.release();
                } catch (IOException e) {
                    log.error("Failed to release the lock", e);
                }
            }
        }

        return false;
    }

    public int indexKeyHashMethod(final String key) {
        int keyHash = key.hashCode();
        int keyHashPositive = Math.abs(keyHash);
        if (keyHashPositive < 0)
            keyHashPositive = 0;
        return keyHashPositive;
    }

    public long getBeginTimestamp() {
        return this.indexHeader.getBeginTimestamp();
    }

    public long getEndTimestamp() {
        return this.indexHeader.getEndTimestamp();
    }

    public long getEndPhyOffset() {
        return this.indexHeader.getEndPhyOffset();
    }

    public boolean isTimeMatched(final long begin, final long end) {
        boolean result = begin < this.indexHeader.getBeginTimestamp() && end > this.indexHeader.getEndTimestamp();
        result = result || (begin >= this.indexHeader.getBeginTimestamp() && begin <= this.indexHeader.getEndTimestamp());
        result = result || (end >= this.indexHeader.getBeginTimestamp() && end <= this.indexHeader.getEndTimestamp());
        return result;
    }

    /**
     * 根据 索引 key 查找消息
     *
     * @param phyOffsets 查找到的消息物理偏移量
     * @param key
     * @param maxNum     本次查找最大消息条数
     * @param begin      开始时间戳
     * @param end        结束时间戳
     * @param lock
     */
    public void selectPhyOffset(final List<Long> phyOffsets, final String key, final int maxNum,
                                final long begin, final long end, boolean lock) {
        if (this.mappedFile.hold()) {
            //计算出key hash值
            int keyHash = indexKeyHashMethod(key);
            //计算出槽位
            int slotPos = keyHash % this.hashSlotNum;
            //计算出槽位的位置 = 索引文件的头大小+槽位*每个槽位大小
            int absSlotPos = IndexHeader.INDEX_HEADER_SIZE + slotPos * hashSlotSize;

            FileLock fileLock = null;
            try {
                if (lock) {
                    // fileLock = this.fileChannel.lock(absSlotPos,
                    // hashSlotSize, true);
                }
                //索引条目序号
                int slotValue = this.mappedByteBuffer.getInt(absSlotPos);
                // if (fileLock != null) {
                // fileLock.release();
                // fileLock = null;
                // }

                if (slotValue <= invalidIndex || slotValue > this.indexHeader.getIndexCount()
                        || this.indexHeader.getIndexCount() <= 1) {
                } else {
                    for (int nextIndexToRead = slotValue; ; ) {
                        if (phyOffsets.size() >= maxNum) {
                            break;
                        }

                        int absIndexPos =
                                IndexHeader.INDEX_HEADER_SIZE + this.hashSlotNum * hashSlotSize
                                        + nextIndexToRead * indexSize;

                        int keyHashRead = this.mappedByteBuffer.getInt(absIndexPos);
                        //消费的物理偏移值
                        long phyOffsetRead = this.mappedByteBuffer.getLong(absIndexPos + 4);
                        //消费的时间
                        long timeDiff = (long) this.mappedByteBuffer.getInt(absIndexPos + 4 + 8);
                        //该条目的前一条Index索引序号
                        int prevIndexRead = this.mappedByteBuffer.getInt(absIndexPos + 4 + 8 + 4);

                        if (timeDiff < 0) {
                            break;
                        }

                        timeDiff *= 1000L;

                        //判断消息的时间范围
                        long timeRead = this.indexHeader.getBeginTimestamp() + timeDiff;
                        boolean timeMatched = (timeRead >= begin) && (timeRead <= end);

                        //保存结果
                        if (keyHash == keyHashRead && timeMatched) {
                            phyOffsets.add(phyOffsetRead);
                        }

                        if (prevIndexRead <= invalidIndex
                                || prevIndexRead > this.indexHeader.getIndexCount()
                                || prevIndexRead == nextIndexToRead || timeRead < begin) {
                            break;
                        }

                        nextIndexToRead = prevIndexRead;
                    }
                }
            } catch (Exception e) {
                log.error("selectPhyOffset exception ", e);
            } finally {
                if (fileLock != null) {
                    try {
                        fileLock.release();
                    } catch (IOException e) {
                        log.error("Failed to release the lock", e);
                    }
                }

                this.mappedFile.release();
            }
        }
    }
}
