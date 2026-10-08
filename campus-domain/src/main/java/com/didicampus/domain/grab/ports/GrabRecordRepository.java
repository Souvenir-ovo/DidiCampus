package com.didicampus.domain.grab.ports;

import com.didicampus.domain.grab.model.GrabRecord;

public interface GrabRecordRepository {

    void insert(GrabRecord record);

    int countGrabbed(long campusId, long errandId);
}
