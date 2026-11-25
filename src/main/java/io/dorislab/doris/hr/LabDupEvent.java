package io.dorislab.doris.hr;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 映射 lab_dup_events（Doris DUPLICATE KEY 模型）。
 * 要点：Doris duplicate key = (event_id, event_time) 两列，但该模型不做任何唯一性约束，
 * 实体侧只取首键列做 @Id（Hibernate 需要 @Id；Doris 侧不会因 id 重复报错——append 语义）。
 */
@Entity
@Table(name = "lab_dup_events")
public class LabDupEvent {

    @Id
    @Column(name = "event_id")
    public Long eventId;

    @Column(name = "event_time")
    public LocalDateTime eventTime;

    @Column(name = "company_id")
    public Long companyId;

    @Column(name = "event_name")
    public String eventName;

    @Column(name = "event_value")
    public BigDecimal eventValue;

    public String payload;

    @Column(name = "attrs_json")
    public String attrsJson;

    public Integer status;

    public String remark;

    public LabDupEvent() {
    }

    public LabDupEvent(Long eventId, LocalDateTime eventTime, Long companyId, String eventName,
                       BigDecimal eventValue, String payload, String attrsJson, Integer status, String remark) {
        this.eventId = eventId;
        this.eventTime = eventTime;
        this.companyId = companyId;
        this.eventName = eventName;
        this.eventValue = eventValue;
        this.payload = payload;
        this.attrsJson = attrsJson;
        this.status = status;
        this.remark = remark;
    }

    @Override
    public String toString() {
        return "LabDupEvent{" + eventId + "," + eventTime + ",co=" + companyId + "," + eventName
                + ",v=" + eventValue + ",st=" + status + ",rk=" + remark + "}";
    }
}
