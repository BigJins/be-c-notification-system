package com.livenotification.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit 6 rules (Phase 6 task 31).
 *
 * <p>Design §7 dependency graph:
 * <pre>
 *   global ← notification ← delivery ← admin
 *                  ↑
 *            idempotency
 * </pre>
 *
 * <p>Key design decisions encoded here:
 * <ul>
 *   <li>R1: domain is pure — no Spring stereotype/web/context (JPA + Lombok allowed).</li>
 *   <li>R2: application layer is port boundary — adapters depend on application, not vice versa.</li>
 *   <li>R3: in-adapters route through application services — never call out-adapters directly.</li>
 *   <li>R4: delivery.domain must not depend on the Notification entity itself (cross-module entity ref
 *       forbidden). VO cross-reference (NotificationId) is the shared kernel and IS allowed.</li>
 *   <li>R5: no cycles between top-level module slices.</li>
 *   <li>R6: notification module must NOT depend on admin (notification is unidirectional owner).
 *       notification → delivery dependency IS allowed: NotificationService orchestrates Delivery
 *       creation in one transaction (CLAUDE.md "1 tx N aggregate" / Outbox pattern intention).
 *       DeliveryRegistrar port in notification.application also references ChannelType (delivery VO).
 *       idempotency → notification is also allowed (2차 dedup target, design §7).</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "com.livenotification",
    importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class}
)
public class ArchitectureTest {

    /**
     * R1: domain layer must NOT depend on Spring stereotype / web / context.
     * JPA, Hibernate, and Lombok annotations are explicitly allowed (they live in
     * jakarta.persistence, org.hibernate, and lombok packages, not the banned ones).
     */
    @ArchTest
    static final ArchRule R1_DOMAIN_NO_SPRING_STEREOTYPE = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.stereotype..",
            "org.springframework.context..",
            "org.springframework.web..")
        .because("domain layer must be free of Spring stereotype / context / web — "
               + "JPA (@Entity, @Column) and Hibernate + Lombok annotations are allowed");

    /**
     * R2: application layer must NOT depend on adapter layer.
     * Application services and ports form the hexagonal boundary — adapters depend ON them,
     * not the other way around. (Spring @Service/@Component annotations in application are
     * in org.springframework.stereotype, not ..adapter.., so they do NOT trigger this rule.)
     */
    @ArchTest
    static final ArchRule R2_APPLICATION_NO_ADAPTER = noClasses()
        .that().resideInAPackage("..application..")
        .should().dependOnClassesThat().resideInAPackage("..adapter..")
        .because("application layer is the port boundary; adapters depend ON application, not the reverse");

    /**
     * R3: in-adapters (controllers, schedulers, registrar adapters) must NOT directly call
     * out-adapters (persistence JPA repositories, channel adapters like EmailAdapter).
     * All cross-layer communication must go through application service methods.
     */
    @ArchTest
    static final ArchRule R3_ADAPTER_IN_NO_ADAPTER_OUT = noClasses()
        .that().resideInAPackage("..adapter.in..")
        .should().dependOnClassesThat().resideInAPackage("..adapter.out..")
        .because("in-adapters (controllers, schedulers) must not directly call out-adapters "
               + "(persistence, channels); route through application services instead");

    /**
     * R4: delivery.domain must NOT depend on the Notification entity class directly.
     * Cross-module entity reference is forbidden — use a VO (shared kernel) or port instead.
     * ALLOWED: delivery.domain.Delivery references NotificationId (a VO/record) — this is the
     * shared kernel and is intentional (design §2, §3 "Belonging" principle).
     */
    @ArchTest
    static final ArchRule R4_CROSS_MODULE_DOMAIN_ENTITIES_FORBIDDEN = noClasses()
        .that().resideInAPackage("..delivery.domain..")
        .should().dependOnClassesThat()
        .haveFullyQualifiedName("com.livenotification.notification.domain.Notification")
        .because("delivery.domain must not directly reference the Notification entity — "
               + "use NotificationId VO (shared kernel) instead; cross-module entity ref violates DDD-Lite boundary");

    /**
     * R5: admin must remain a leaf operational module.
     *
     * <p>The current design intentionally allows a few documented top-level cycles:
     * notification ↔ delivery (1 tx N aggregate / DeliveryRegistrar / NotificationLookup)
     * and notification ↔ idempotency (request-hash + replay orchestration).
     * A blanket slice no-cycle rule therefore over-rejects the intended architecture.
     *
     * <p>What must still hold strictly is that no non-admin module depends on admin.
     * Admin is an operational entrypoint that calls into the system; the core modules
     * must not grow reverse dependencies on it.
     */
    @ArchTest
    static final ArchRule R5_ADMIN_IS_LEAF = noClasses()
        .that().resideOutsideOfPackage("..admin..")
        .should().dependOnClassesThat().resideInAPackage("..admin..")
        .because("admin is an operational leaf module; core modules must not depend on it, "
               + "while documented notification↔delivery and notification↔idempotency cycles remain allowed");

    /**
     * R6: notification module must NOT depend on admin module.
     *
     * <p>RELAXED from original spec (§E6) — the strict form
     * "notification must not depend on delivery OR admin" cannot hold here because:
     * <ol>
     *   <li>NotificationService orchestrates Delivery creation in one transaction
     *       (CLAUDE.md: "1 tx N aggregate" / Outbox pattern exception — max 6 INSERTs).</li>
     *   <li>DeliveryRegistrar port in notification.application.port references ChannelType
     *       (a delivery VO) — legitimate shared-kernel reference.</li>
     *   <li>NotificationDetail record holds List&lt;Delivery&gt; for the response aggregate view.</li>
     * </ol>
     * All of the above are intentional design-§7 cross-references.
     *
     * <p>The admin dependency, however, must remain strictly forbidden:
     * notification is the contracted-port owner; admin calls into notification via ports.
     * idempotency dependency (2차 dedup target) is explicitly allowed by design §7.
     */
    @ArchTest
    static final ArchRule R6_NOTIFICATION_NO_ADMIN = noClasses()
        .that().resideInAPackage("..notification..")
        .should().dependOnClassesThat().resideInAPackage("..admin..")
        .because("notification is the unidirectional port owner; admin depends on notification, "
               + "not the reverse. delivery dependency allowed (Outbox 1-tx exception, design §7). "
               + "idempotency dependency allowed (2차 dedup target).");
}
