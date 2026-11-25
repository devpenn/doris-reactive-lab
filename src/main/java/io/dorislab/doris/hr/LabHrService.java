package io.dorislab.doris.hr;

import java.time.LocalDateTime;
import java.util.List;

import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * HR/Panache 场景四的 CDI 服务载体：@WithSession / @WithTransaction 拦截器必须挂在
 * CDI bean 方法上才生效（测试类直调不可靠），测试经此服务走标准用法（对齐 生产项目模式）。
 */
@ApplicationScoped
public class LabHrService {

    @Inject
    LabUkUserRepository userRepository;

    @Inject
    LabDupEventRepository eventRepository;

    @Inject
    LabAggMetricRepository aggRepository;

    // ─── 读写路径（@WithSession） ───

    @WithSession
    public Uni<LabUkUser> findUser(Long id) {
        return userRepository.findById(id);
    }

    @WithSession
    public Uni<List<LabUkUser>> allUsers() {
        return userRepository.findAll().list();
    }

    @WithSession
    public Uni<LabDupEvent> findEvent(Long id) {
        return eventRepository.findById(id);
    }

    @WithSession
    public Uni<LabAggMetric> findAgg(LabAggMetricId id) {
        return aggRepository.findById(id);
    }

    @WithSession
    public Uni<LabUkUser> persistUser(LabUkUser user) {
        return userRepository.persist(user);
    }

    @WithSession
    public Uni<LabDupEvent> persistEvent(LabDupEvent event) {
        return eventRepository.persist(event);
    }

    @WithSession
    public Uni<Void> deleteUser(LabUkUser user) {
        return userRepository.delete(user);
    }

    @WithSession
    public Uni<Boolean> deleteEventById(Long id) {
        return eventRepository.deleteById(id);
    }

    /** 分页：Panache findAll().page()（观察生成 SQL 的 LIMIT 形态） */
    @WithSession
    public Uni<List<LabUkUser>> userPage(int pageIndex, int pageSize) {
        return userRepository.findAll().page(pageIndex, pageSize).list();
    }

    /** 显式排序分页（生产形态） */
    @WithSession
    public Uni<List<LabUkUser>> sortedPage(int pageIndex, int pageSize) {
        return userRepository.findAll(io.quarkus.panache.common.Sort.ascending("userId"))
                .page(pageIndex, pageSize).list();
    }

    @WithSession
    public Uni<Long> userCount() {
        return userRepository.count();
    }

    // ─── 事务路径（@WithTransaction：BEGIN...COMMIT/ROLLBACK 语义实验） ───

    @WithTransaction
    public Uni<Void> txnInsertTwoUsers(LabUkUser a, LabUkUser b) {
        return userRepository.persist(a)
                .chain(x -> userRepository.persist(b))
                .replaceWithVoid();
    }

    /** 事务内写入后抛异常，观察 ROLLBACK 是否真的撤掉 INSERT */
    @WithTransaction
    public Uni<Void> txnInsertThenFail(LabUkUser a) {
        return userRepository.persist(a)
                .chain(() -> Uni.createFrom().<Void>failure(new IllegalStateException("LAB-ROLLBACK-PROBE: 事务内故意失败")));
    }

    /** 事务内 UPDATE 后抛异常，观察 ROLLBACK 是否撤掉 UPDATE（unique MOW） */
    @WithTransaction
    public Uni<Void> txnUpdateThenFail(Long userId, String newName) {
        return userRepository.findById(userId)
                .chain(u -> {
                    u.userName = newName;
                    return userRepository.persist(u);
                })
                .chain(() -> Uni.createFrom().<Void>failure(new IllegalStateException("LAB-ROLLBACK-PROBE: 更新后故意失败")));
    }

    /** duplicate 模型表上走事务（观察 Doris 对非 unique 模型的事务 DML 支持） */
    @WithTransaction
    public Uni<LabDupEvent> txnInsertDupEvent(LabDupEvent e) {
        return eventRepository.persist(e);
    }

    /** 普通读走 @WithTransaction（读-only 事务在 Doris 上的开销/行为） */
    @WithTransaction
    public Uni<Long> txnReadCount() {
        return userRepository.count();
    }

    /** session 级原生查询（D4） */
    @WithSession
    public Uni<List<Object[]>> nativeEventsByCompany(Long companyId) {
        //noinspection unchecked
        return eventRepository.getSession()
                .chain(s -> s.createNativeQuery(
                        "SELECT event_id, event_name, event_value FROM lab_dup_events WHERE company_id = ? ORDER BY event_id",
                        Object[].class)
                        .setParameter(1, companyId)
                        .getResultList());
    }

    /** session 级原生写入（INSERT 直写 unique 模型） */
    @WithSession
    public Uni<Integer> nativeInsertUser(LabUkUser u) {
        return eventRepository.getSession()
                .chain(s -> s.createNativeQuery(
                        "INSERT INTO lab_uk_users (user_id, user_name, email, balance, status, gmt_create, gmt_modified, ext_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                        .setParameter(1, u.userId)
                        .setParameter(2, u.userName)
                        .setParameter(3, u.email)
                        .setParameter(4, u.balance)
                        .setParameter(5, u.status)
                        .setParameter(6, u.gmtCreate)
                        .setParameter(7, u.gmtModified)
                        .setParameter(8, u.extJson)
                        .executeUpdate());
    }

    /** native count（D2c 同 id 双 append 验证） */
    @WithSession
    public Uni<Long> nativeCountDupEvents(Long eventId) {
        return eventRepository.getSession()
                .chain(s -> s.createNativeQuery(
                        "SELECT count(*) FROM lab_dup_events WHERE event_id = " + eventId, Long.class)
                        .getSingleResult());
    }

    /** native DELETE（D3d aggregate 模型删除条件实验） */
    @WithSession
    public Uni<Integer> nativeDeleteAgg(String sql) {
        return aggRepository.getSession()
                .chain(s -> s.createNativeQuery(sql).executeUpdate());
    }

    public static LabUkUser user(long id, String name) {
        return new LabUkUser(id, name, name + "@lab.local",
                java.math.BigDecimal.valueOf(id).movePointLeft(3),
                1,
                LocalDateTime.of(2024, 6, 1, 8, 0, 0),
                LocalDateTime.of(2024, 6, 1, 9, 0, 0),
                "{\"lab\":true}");
    }
}
