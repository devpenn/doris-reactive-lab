package io.dorislab.doris.hr;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * 映射 lab_agg_metrics（Doris AGGREGATE KEY 模型，复合 @EmbeddedId）。
 * 实验点：聚合模型表做实体映射是否可行（读侧）；写侧聚合语义由原生 SQL 场景验证。
 */
@Entity
@Table(name = "lab_agg_metrics")
public class LabAggMetric {

    @EmbeddedId
    public LabAggMetricId id;

    @Column(name = "total_value")
    public Long totalValue;

    @Column(name = "last_value")
    public Long lastValue;

    @Column(name = "max_value")
    public Long maxValue;

    @Override
    public String toString() {
        return "LabAggMetric{" + id + ",total=" + totalValue + ",last=" + lastValue + ",max=" + maxValue + "}";
    }
}
