package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "change_requests")
public class ChangeRequest extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String title;
    @ManyToOne(optional = false) @JoinColumn(name = "connection_id") public DbConnection connection;
    @ManyToOne @JoinColumn(name = "project_id") public Project project;
    @Lob public String reason;
    @Lob public String purpose;
    @Column(name = "ticket_ref") public String ticketRef;
    public String status = "DRAFT";
    @Column(name = "target_schemas") public String targetSchemas;
    @Column(name = "requested_by") public String requestedBy;
    @Column(name = "created_at") public OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "updated_at") public OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "submitted_at") public OffsetDateTime submittedAt;
    @Column(name = "decided_at") public OffsetDateTime decidedAt;
    @Column(name = "executed_at") public OffsetDateTime executedAt;

    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("seq") public List<RequestOperation> operations = new ArrayList<>();
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("decidedAt, id") public List<Approval> approvals = new ArrayList<>();
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt, id") public List<RequestComment> comments = new ArrayList<>();
    @OneToMany(mappedBy = "request", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startedAt, id") public List<Execution> executions = new ArrayList<>();

    public String code() {
        return String.format("SOL-%06d", id);
    }

    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
