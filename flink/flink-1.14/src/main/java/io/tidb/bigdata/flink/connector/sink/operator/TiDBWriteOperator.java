/*
 * Copyright 2022 TiDB Project Authors.
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

package io.tidb.bigdata.flink.connector.sink.operator;

import io.tidb.bigdata.flink.connector.sink.TiDBSinkOptions;
import io.tidb.bigdata.tidb.ClientConfig;
import io.tidb.bigdata.tidb.ClientSession;
import io.tidb.bigdata.tidb.RowBuffer;
import io.tidb.bigdata.tidb.TiDBEncodeHelper;
import io.tidb.bigdata.tidb.TiDBWriteHelper;
import io.tidb.bigdata.tidb.allocator.DynamicRowIDAllocator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.BoundedOneInput;
import org.apache.flink.streaming.api.operators.OneInputStreamOperator;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tikv.common.meta.TiTableInfo;
import org.tikv.common.meta.TiTimestamp;
import org.tikv.common.row.Row;

public abstract class TiDBWriteOperator extends AbstractStreamOperator<Void> implements
    OneInputStreamOperator<Row, Void>, BoundedOneInput {

  private static final Logger LOG = LoggerFactory.getLogger(TiDBWriteOperator.class);

  protected final String databaseName;
  protected final String tableName;
  private final Map<String, String> properties;
  protected final TiTimestamp tiTimestamp;
  protected final TiDBSinkOptions sinkOptions;
  private final List<Long> rowIdStarts;

  protected byte[] primaryKey;

  protected transient ClientSession session;
  protected transient TiDBEncodeHelper tiDBEncodeHelper;
  protected transient TiDBWriteHelper tiDBWriteHelper;
  protected transient TiTableInfo tiTableInfo;
  protected transient RowBuffer buffer;
  protected transient DynamicRowIDAllocator rowIDAllocator;

  public TiDBWriteOperator(String databaseName, String tableName,
      Map<String, String> properties, TiTimestamp tiTimestamp, TiDBSinkOptions sinkOption,
      byte[] primaryKey, List<Long> rowIdStarts) {
    this.databaseName = databaseName;
    this.tableName = tableName;
    this.properties = properties;
    this.tiTimestamp = tiTimestamp;
    this.sinkOptions = sinkOption;
    this.primaryKey = primaryKey;
    this.rowIdStarts = rowIdStarts;
  }

  @Override
  public void open() throws Exception {
    int indexOfThisSubtask = getRuntimeContext().getIndexOfThisSubtask();
    Long start = rowIdStarts.get(indexOfThisSubtask);
    this.session = ClientSession.create(new ClientConfig(properties));
    this.tiTableInfo = session.getTableMust(databaseName, tableName);
    this.rowIDAllocator = new DynamicRowIDAllocator(session, databaseName, tableName,
        sinkOptions.getRowIdAllocatorStep(), start);

    openInternal();
  }

  protected abstract void openInternal();

  @Override
  public void close() throws Exception {
    if (session != null) {
      session.close();
    }
    Optional.ofNullable(tiDBWriteHelper).ifPresent(TiDBWriteHelper::close);
    Optional.ofNullable(tiDBEncodeHelper).ifPresent(TiDBEncodeHelper::close);
  }

  protected abstract void flushRows();

  @Override
  public void processElement(StreamRecord<Row> element) throws Exception {
    Row row = element.getValue();
    if (buffer.isFull()) {
      flushRows();
    }
    boolean added = buffer.add(row);
    if (!added && !sinkOptions.isDeduplicate()) {
      throw new IllegalStateException(
          "Duplicate index in one batch, please enable deduplicate, row = " + row);
    }
  }

  @Override
  public void endInput() throws Exception {
    if (buffer.size() != 0) {
      flushRows();
    }
  }

}
