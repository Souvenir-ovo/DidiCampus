package com.didicampus.domain.errand.ports;

import com.didicampus.domain.errand.model.Errand;

import java.time.Instant;
import java.util.List;

/**
 * 任务查询端口。读模型与写聚合分开，避免查询需求不断扩大写仓储接口。
 */
public interface ErrandQueryPort {

    List<Errand> list(long campusId, String status, int page, int size);

    List<CursorItem> listByCursor(long campusId, String status,
                                  Instant beforeCreatedAt, Long beforeId, int size);

    List<Errand> listByPublisher(long publisherId, int page, int size);

    List<Errand> listByRunner(long runnerId, int page, int size);

    List<StatusChange> statusLog(long campusId, long errandId);

    List<Long> sampleIds(int limit);

    int countOngoingByRunner(long runnerId);

    record StatusChange(Instant time, String from, String to, int round, long operatorId) {}

    record CursorItem(Errand errand, Instant createdAt) {}
}
