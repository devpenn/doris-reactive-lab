package io.dorislab.doris.hr;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** lab_agg_metrics（AGGREGATE KEY 模型）复合主键：三列 aggregate key */
@Embeddable
public class LabAggMetricId implements Serializable {

    @Column(name = "biz_date")
    public LocalDate bizDate;

    @Column(name = "company_id")
    public Long companyId;

    @Column(name = "metric_name")
    public String metricName;

    public LabAggMetricId() {
    }

    public LabAggMetricId(LocalDate bizDate, Long companyId, String metricName) {
        this.bizDate = bizDate;
        this.companyId = companyId;
        this.metricName = metricName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LabAggMetricId that)) return false;
        return Objects.equals(bizDate, that.bizDate)
                && Objects.equals(companyId, that.companyId)
                && Objects.equals(metricName, that.metricName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bizDate, companyId, metricName);
    }

    @Override
    public String toString() {
        return "LabAggMetricId{" + bizDate + "," + companyId + "," + metricName + "}";
    }
}
