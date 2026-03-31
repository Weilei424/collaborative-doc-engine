package com.mwang.backend.config;

import com.mwang.backend.domain.Document;
import com.mwang.backend.domain.DocumentOperation;
import com.mwang.backend.domain.DocumentOperationType;
import com.mwang.backend.domain.DocumentPermission;
import com.mwang.backend.domain.DocumentVisibility;
import com.mwang.backend.domain.User;
import com.mwang.backend.repositories.DocumentOperationRepository;
import com.mwang.backend.repositories.DocumentRepository;
import com.mwang.backend.repositories.UserRepository;
import com.mwang.backend.service.AuthService;
import com.mwang.backend.web.model.RegisterRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final DocumentRepository documentRepository;
    private final DocumentOperationRepository operationRepository;
    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername("alice")) {
            log.info("[DemoDataSeeder] Demo data already present, skipping.");
            return;
        }
        seed();
    }

    @Transactional
    protected void seed() {

        // ----------------------------------------------------------------
        // 1. Users
        // ----------------------------------------------------------------
        User alice = registerUser("alice", "alice@demo.test");
        User bob   = registerUser("bob",   "bob@demo.test");
        User carol = registerUser("carol", "carol@demo.test");
        User dave  = registerUser("dave",  "dave@demo.test");
        User eve   = registerUser("eve",   "eve@demo.test");

        // ----------------------------------------------------------------
        // 2. Paragraph arrays (used for both Document.content and operations)
        // ----------------------------------------------------------------
        String[] roadmapTexts = {
            "This roadmap outlines our key initiatives for Q3, focusing on platform stability, user growth, and the launch of collaborative editing features.",
            "Theme 1 - Reliability: Reduce P0 incident rate by 40% through improved observability, automated alerting, and on-call runbook updates.",
            "Theme 2 - Growth: Launch referral program targeting a 20% increase in monthly active users by end of quarter.",
            "Theme 3 - Collaboration: Ship real-time collaborative editing to all paid tiers. Target: <200ms p95 operation latency.",
            "Dependencies: Infrastructure team to complete Redis cluster migration by Week 4. Design team to deliver updated editor UI by Week 3.",
            "Risks: Kafka consumer lag under peak load. Mitigation: partition increase scheduled for Week 2."
        };

        String[] apiGuideTexts = {
            "All public APIs follow RESTful conventions with JSON request and response bodies. Endpoints are versioned under /api/v1/.",
            "Authentication: All endpoints except /api/auth/register and /api/auth/login require a valid JWT in the Authorization: Bearer header.",
            "Error responses use a standard envelope: { code, message, details }. HTTP status codes map to application error categories.",
            "Pagination: list endpoints accept page, size, and sort parameters. Responses include totalElements, totalPages, and a content array.",
            "WebSocket collaboration uses STOMP 1.2 over SockJS. Clients authenticate via the token query parameter on the upgrade request."
        };

        String[] designSystemTexts = {
            "Design System v2 introduces a unified token architecture for color, spacing, and typography across web and mobile surfaces.",
            "Color tokens are organized into three tiers: primitive (raw hex values), semantic (intent-based aliases), and component-level overrides.",
            "Typography scale uses a 4px base grid with a modular type scale ratio of 1.25. All text styles are defined as Tailwind utility classes.",
            "Component library ships as a shared npm package. Each component exports a React component, a Storybook story, and accessibility annotations."
        };

        String[] userResearchTexts = {
            "This report summarizes findings from 24 user interviews conducted in February. Participants were existing customers across three plan tiers.",
            "Top pain point (67% of respondents): losing work due to conflicting saves when multiple team members edit the same document.",
            "Most requested feature (58%): real-time presence indicators showing who is currently viewing or editing a document.",
            "Recommendation: prioritize live collaboration and conflict resolution in Q3. Secondary: improve search and document organization."
        };

        String[] sprintTexts = {
            "Sprint 14 runs from March 31 to April 11. Capacity: 3 engineers at 8 points each = 24 points total.",
            "Priority 1 - Operation latency regression (8 pts): Profiling shows N+1 query in DocumentOperationServiceImpl under concurrent load. Fix and add regression test.",
            "Priority 2 - Collaborator management UI (8 pts): Wire DocumentSettingsPage to the real /api/collaborators endpoints. Remove mock data.",
            "Priority 3 - Load benchmark (5 pts): Implement k6 benchmark script and record baseline numbers for Q3 roadmap performance claims.",
            "Carry-over: Redis cluster migration deferred to Sprint 15 pending infrastructure team availability."
        };

        String[] onboardingTexts = {
            "Welcome to the team. This checklist covers everything you need to get set up in your first week.",
            "Day 1: Set up dev environment using the README quickstart. Register an account at localhost:3000. Create your first document.",
            "Day 2-3: Read the Architecture Notes and API Design Guidelines documents. Shadow an on-call engineer.",
            "Week 1 goal: Submit your first pull request. Pair with a teammate on an open issue from the backlog."
        };

        String[] wikiTexts = {
            "This wiki is the single source of truth for team processes, decisions, and documentation.",
            "Getting Started: See the Onboarding Checklist for new team members. Engineering standards live in API Design Guidelines.",
            "Active Projects: Q3 Product Roadmap (alice), Design System v2 (carol), Release Notes v2.1 (bob).",
            "How to contribute: Any team member with WRITE access can edit this document. Propose structural changes in #wiki-updates."
        };

        String[] dbNotesTexts = {
            "Notes on the V3 Flyway migration: passwordHash column made nullable to support OAuth users without local credentials.",
            "V4 adds user search indexes on username and email. Query: SELECT * FROM users WHERE username ILIKE '%query%' OR email ILIKE '%query%'.",
            "Planned V5: add full-text search index on document title and content using pg_trgm. Estimated impact: 2-5x speedup on /api/documents?query=.",
            "Never run ddl-auto=create or update in non-test environments. Flyway owns all schema changes."
        };

        String[] personalTexts = {
            "This week: finish Design System v2 color token documentation, review Marketing Copy Drafts, prep Design System v2 demo.",
            "Blocked: waiting on alice to approve the semantic color naming convention before publishing the npm package.",
            "Next sprint: component accessibility audit, Storybook upgrade to v8, icon library handoff to engineering.",
            "Long term: propose migration from CSS Modules to CSS-in-JS for the editor surface."
        };

        String[] marketingTexts = {
            "Real-time collaborative document editing for teams who move fast. Edit together, ship faster.",
            "Feature highlight - Live collaboration: See your teammates changes as they type. No more version conflicts, no more lost work.",
            "Feature highlight - Access control: Share documents with your whole team or keep them private. Granular permissions for every collaborator.",
            "Feature highlight - Full edit history: Every change is recorded. Roll back to any version, see who changed what and when.",
            "CTA: Start collaborating in minutes. No credit card required."
        };

        String[] kpiTexts = {
            "Key metrics tracked this quarter: MAU, document creation rate, collaboration session duration, p95 operation latency, Kafka consumer lag.",
            "MAU target: 10,000 by end of Q3. Current: 6,200 (+12% MoM). On track if growth rate holds.",
            "Collaboration session duration median: 8.4 minutes. Users who invite at least one collaborator retain at 2.3x the rate of solo users.",
            "Infrastructure: p95 operation latency at 87ms under 30 concurrent users. Target is <200ms at 100 concurrent users."
        };

        String[] releaseTexts = {
            "Version 2.1 ships real-time collaborative editing, JWT authentication, and a redesigned document dashboard.",
            "New: Real-time collaboration. Multiple users can now edit the same document simultaneously. Changes are broadcast via WebSocket and persisted to an immutable operation log.",
            "New: JWT authentication. All API endpoints and WebSocket connections now require a signed JWT. Tokens are issued at /api/auth/register and /api/auth/login.",
            "New: Document dashboard. Browse your owned, shared, and public documents. Search by title, manage collaborators, and transfer ownership.",
            "Fixed: Concurrent operation submission now correctly serializes through pessimistic locking. Duplicate operationId submissions are idempotent."
        };

        // ----------------------------------------------------------------
        // 3. Documents
        // ----------------------------------------------------------------
        Document roadmap      = saveDoc("Q3 Product Roadmap",        alice, DocumentVisibility.SHARED,  roadmapTexts);
        Document apiGuide     = saveDoc("API Design Guidelines",      bob,   DocumentVisibility.SHARED,  apiGuideTexts);
        Document designSystem = saveDoc("Design System v2",           carol, DocumentVisibility.SHARED,  designSystemTexts);
        Document userResearch = saveDoc("User Research Report",       alice, DocumentVisibility.SHARED,  userResearchTexts);
        Document sprint       = saveDoc("Sprint 14 Planning",         bob,   DocumentVisibility.SHARED,  sprintTexts);
        Document onboarding   = saveDoc("Onboarding Checklist",       alice, DocumentVisibility.PUBLIC,  onboardingTexts);
        Document wiki         = saveDoc("Company Wiki Home",           alice, DocumentVisibility.PUBLIC,  wikiTexts);
        Document dbNotes      = saveDoc("Database Migration Notes",   bob,   DocumentVisibility.PRIVATE, dbNotesTexts);
        Document personal     = saveDoc("Personal Task List",         carol, DocumentVisibility.PRIVATE, personalTexts);
        Document marketing    = saveDoc("Marketing Copy Drafts",      alice, DocumentVisibility.SHARED,  marketingTexts);
        Document kpi          = saveDoc("Q3 KPI Dashboard Notes",     dave,  DocumentVisibility.SHARED,  kpiTexts);
        Document release      = saveDoc("Release Notes v2.1",         bob,   DocumentVisibility.SHARED,  releaseTexts);

        // ----------------------------------------------------------------
        // 4. Collaborators
        // ----------------------------------------------------------------
        roadmap.addCollaborator(bob,   DocumentPermission.WRITE);
        roadmap.addCollaborator(carol, DocumentPermission.READ);
        roadmap.addCollaborator(eve,   DocumentPermission.READ);
        documentRepository.save(roadmap);

        apiGuide.addCollaborator(alice, DocumentPermission.ADMIN);
        apiGuide.addCollaborator(carol, DocumentPermission.READ);
        documentRepository.save(apiGuide);

        designSystem.addCollaborator(alice, DocumentPermission.WRITE);
        designSystem.addCollaborator(bob,   DocumentPermission.READ);
        documentRepository.save(designSystem);

        sprint.addCollaborator(alice, DocumentPermission.WRITE);
        sprint.addCollaborator(carol, DocumentPermission.READ);
        sprint.addCollaborator(dave,  DocumentPermission.READ);
        documentRepository.save(sprint);

        marketing.addCollaborator(carol, DocumentPermission.WRITE);
        marketing.addCollaborator(bob,   DocumentPermission.READ);
        documentRepository.save(marketing);

        kpi.addCollaborator(alice, DocumentPermission.READ);
        kpi.addCollaborator(bob,   DocumentPermission.WRITE);
        documentRepository.save(kpi);

        release.addCollaborator(alice, DocumentPermission.ADMIN);
        release.addCollaborator(dave,  DocumentPermission.READ);
        documentRepository.save(release);

        // ----------------------------------------------------------------
        // 5. Operations
        // ----------------------------------------------------------------
        seedOps(roadmap,      alice, roadmapTexts);
        seedOps(apiGuide,     bob,   apiGuideTexts);
        seedOps(designSystem, carol, designSystemTexts);
        seedOps(userResearch, alice, userResearchTexts);
        seedOps(sprint,       bob,   sprintTexts);
        seedOps(onboarding,   alice, onboardingTexts);
        seedOps(wiki,         alice, wikiTexts);
        seedOps(dbNotes,      bob,   dbNotesTexts);
        seedOps(personal,     carol, personalTexts);
        seedOps(marketing,    alice, marketingTexts);
        seedOps(kpi,          dave,  kpiTexts);
        seedOps(release,      bob,   releaseTexts);

        log.info("[DemoDataSeeder] Seeded demo data: 5 users, 12 documents, 16 collaborator entries, 58 operations");
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private User registerUser(String username, String email) {
        var resp = authService.register(new RegisterRequest(username, email, "demo1234"));
        return userRepository.findById(resp.userId()).orElseThrow();
    }

    private Document saveDoc(String title, User owner, DocumentVisibility visibility, String... paragraphs) {
        return documentRepository.save(Document.builder()
                .title(title)
                .owner(owner)
                .visibility(visibility)
                .content(buildContent(paragraphs))
                .currentVersion((long) paragraphs.length)
                .build());
    }

    private String buildContent(String... paragraphs) {
        StringBuilder sb = new StringBuilder("{\"children\":[");
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"paragraph\",\"text\":\"")
              .append(jsonEscape(paragraphs[i]))
              .append("\",\"children\":[]}");
        }
        return sb.append("]}").toString();
    }

    private void seedOps(Document doc, User actor, String... texts) {
        for (int i = 0; i < texts.length; i++) {
            operationRepository.save(DocumentOperation.builder()
                    .document(doc)
                    .actor(actor)
                    .operationId(UUID.randomUUID())
                    .clientSessionId("seed-session")
                    .baseVersion((long) i)
                    .serverVersion((long) (i + 1))
                    .operationType(DocumentOperationType.INSERT_TEXT)
                    .payload("{\"path\":[" + i + "],\"offset\":0,\"text\":\"" + jsonEscape(texts[i]) + "\"}")
                    .build());
        }
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
