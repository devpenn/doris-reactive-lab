package io.dorislab.doris.hr;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 映射 lab_uk_users（Doris UNIQUE KEY 模型，MOW）。
 * 要点：无自增主键（Doris unique key 即业务主键），@Id 手工赋值，无 @GeneratedValue。
 */
@Entity
@Table(name = "lab_uk_users")
public class LabUkUser {

    @Id
    @Column(name = "user_id")
    public Long userId;

    @Column(name = "user_name")
    public String userName;

    public String email;

    public BigDecimal balance;

    public Integer status;

    @Column(name = "gmt_create")
    public LocalDateTime gmtCreate;

    @Column(name = "gmt_modified")
    public LocalDateTime gmtModified;

    @Column(name = "ext_json")
    public String extJson;

    public LabUkUser() {
    }

    public LabUkUser(Long userId, String userName, String email, BigDecimal balance, Integer status,
                     LocalDateTime gmtCreate, LocalDateTime gmtModified, String extJson) {
        this.userId = userId;
        this.userName = userName;
        this.email = email;
        this.balance = balance;
        this.status = status;
        this.gmtCreate = gmtCreate;
        this.gmtModified = gmtModified;
        this.extJson = extJson;
    }

    @Override
    public String toString() {
        return "LabUkUser{" + userId + "," + userName + ",balance=" + balance + ",status=" + status
                + ",gmtCreate=" + gmtCreate + ",gmtModified=" + gmtModified + ",extJson=" + extJson + "}";
    }
}
