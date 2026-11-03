package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLog extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public OffsetDateTime at = OffsetDateTime.now();
    public String username;
    public String action;
    @Column(name = "entity_type") public String entityType;
    @Column(name = "entity_id") public String entityId;
    @Lob public String details;
    public String ip;
}
