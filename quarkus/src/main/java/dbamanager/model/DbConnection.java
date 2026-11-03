package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/** Base de datos administrada. Cada una puede estar en un servidor distinto. */
@Entity
@Table(name = "db_connections")
public class DbConnection extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String name;
    public String engine = "oracle";
    public String environment;
    public String description;
    public String host;
    public int port;
    @Column(name = "service_name") public String serviceName;
    /** Cuenta de solo diccionario para explorar estructura (nunca datos). */
    @Column(name = "catalog_user") public String catalogUser;
    @Lob @Column(name = "catalog_password_enc") public String catalogPasswordEnc;
    /** Cuenta privilegiada que ejecuta solicitudes aprobadas (opcional). */
    @Column(name = "exec_user") public String execUser;
    @Lob @Column(name = "exec_password_enc") public String execPasswordEnc;
    @Convert(converter = NumericBooleanConverter.class) @Column(name = "allow_execution") public boolean allowExecution;
    @Convert(converter = NumericBooleanConverter.class) @Column(name = "use_tls") public boolean useTls;
    @Column(name = "server_version") public String serverVersion;
    @Column(name = "required_approvals") public int requiredApprovals = 1;
    @Convert(converter = NumericBooleanConverter.class) public boolean active = true;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at") public OffsetDateTime createdAt = OffsetDateTime.now();
}
