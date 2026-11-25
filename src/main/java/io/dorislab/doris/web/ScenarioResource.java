package io.dorislab.doris.web;

import java.util.List;
import java.util.Map;

import io.dorislab.doris.scenario.BindingScenarios;
import io.dorislab.doris.scenario.CompareScenarios;
import io.dorislab.doris.scenario.ExtraScenarios;
import io.dorislab.doris.scenario.HrScenarios;
import io.dorislab.doris.scenario.PoolScenarios;
import io.dorislab.doris.scenario.StepResult;
import io.dorislab.doris.scenario.TimeoutScenarios;
import io.dorislab.doris.scenario.TypeScenarios;
import io.dorislab.doris.support.LabSetup;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.Response;

/**
 * 场景执行端点（e2e 层经 HTTP 驱动）：Java 侧只执行场景并返回结构化结果，
 * 断言与报告由 e2e/ Python 用例层负责。
 * A/B/C/E/F/G 组跑在 worker 线程（@Blocking，含 sleep/程序化池等阻塞操作）；
 * D 组跑在事件循环（@WithSession 拦截器要求 Vertx 上下文，与生产路径一致）。
 */
@Path("/scenario")
@ApplicationScoped
public class ScenarioResource {

    @Inject
    PoolScenarios poolScenarios;

    @Inject
    BindingScenarios bindingScenarios;

    @Inject
    TypeScenarios typeScenarios;

    @Inject
    HrScenarios hrScenarios;

    @Inject
    CompareScenarios compareScenarios;

    @Inject
    TimeoutScenarios timeoutScenarios;

    @Inject
    ExtraScenarios extraScenarios;

    @Inject
    io.dorislab.doris.scenario.HrLegScenarios hrLegScenarios;

    @GET
    @Path("a")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupA() {
        return Response.ok(poolScenarios.runAll()).build();
    }

    @GET
    @Path("b")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupB() {
        return Response.ok(bindingScenarios.runAll()).build();
    }

    @GET
    @Path("c")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupC() {
        return Response.ok(typeScenarios.runAll()).build();
    }

    @GET
    @Path("d")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<StepResult>> groupD() {
        // 注意：必须以 Uni 作为方法返回类型（RESTEasy Reactive 自动订阅并序列化结果），
        // Response.ok(Uni) 会把 Uni 本身当实体交给 Jackson 序列化而 500
        return hrScenarios.runAll();
    }

    @GET
    @Path("e")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupE() {
        return Response.ok(compareScenarios.runAll()).build();
    }

    @GET
    @Path("f")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupF() {
        return Response.ok(timeoutScenarios.runAll()).build();
    }

    @GET
    @Path("g")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response groupG() {
        return Response.ok(extraScenarios.runAll()).build();
    }

    /** HR 腿专用端点（eventloop 限定，无 @Blocking）：B/C/E 组的 Hibernate Reactive 数据腿 */
    @GET
    @Path("b-hr")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<StepResult>> groupBHr() {
        return hrLegScenarios.runBHr();
    }

    @GET
    @Path("c-hr")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<StepResult>> groupCHr() {
        return hrLegScenarios.runCHr();
    }

    @GET
    @Path("e-hr")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<StepResult>> groupEHr() {
        return hrLegScenarios.runEHr();
    }

    /** 建表+种子（e2e 起应用后先调用；reseed=true 只重灌种子）。@Blocking：LabSetup 自建池不允许在事件循环线程上创建 */
    @POST
    @Path("setup")
    @Produces(MediaType.APPLICATION_JSON)
    @io.smallrye.common.annotation.Blocking
    public Response setup(@QueryParam("reseed") boolean reseed) {
        String result;
        if (reseed) {
            LabSetup.reseed();
            result = "reseeded";
        } else {
            result = LabSetup.ensure();
        }
        return Response.ok(Map.of("result", result)).build();
    }
}
