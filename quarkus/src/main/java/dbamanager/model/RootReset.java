package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "root_resets")
public class RootReset extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "requested_by") public String requestedBy;
    @Lob public String reason;
    @Column(name = "requested_at") public OffsetDateTime requestedAt = OffsetDateTime.now();
    @Column(name = "effective_at") public OffsetDateTime effectiveAt;
    /** WAITING, CANCELLED, DONE. */
    public String status;
    @Column(name = "closed_by") public String closedBy;
    @Column(name = "closed_at") public OffsetDateTime closedAt;
}
