package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/** Esquemas que un DBA con rol dba puede aprobar/ejecutar. "*" = todos los de la conexión. */
@Entity
@Table(name = "approver_scopes")
public class ApproverScope extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String username;
    @Column(name = "connection_id") public Long connectionId;
    @Column(name = "schema_name") public String schemaName;
    @Column(name = "granted_by") public String grantedBy;
    @Column(name = "granted_at") public OffsetDateTime grantedAt = OffsetDateTime.now();
    /** Llave del DBA principal que otorgó el alcance, y su firma. */
    @ManyToOne @JoinColumn(name = "key_id") public DbaKey key;
    @Lob public String signature;
}
