package io.dorislab.doris.hr;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class LabUkUserRepository implements PanacheRepositoryBase<LabUkUser, Long> {
}
