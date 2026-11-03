package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "request_operations")
public class RequestOperation extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "request_id") public ChangeRequest request;
    public int seq;
    @Column(name = "op_type") public String opType;
    @Column(name = "target_schema") public String targetSchema;
    @Column(name = "object_name") public String objectName;
    @Lob public String payload;
    /** JSON: sentencias generadas. */
    @Lob public String statements;
    public String status = "PENDING";
    /** Sentencias de esta operación ya ejecutadas: un reintento continúa desde la siguiente. */
    @Column(name = "statements_done") public int statementsDone;
    @Lob public String message;
    @Column(name = "executed_at") public OffsetDateTime executedAt;
}
