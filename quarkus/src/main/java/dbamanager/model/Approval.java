package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "approvals")
public class Approval extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "request_id") public ChangeRequest request;
    public String approver;
    /** APPROVED / REJECTED / CHANGES_REQUESTED. */
    public String decision;
    @Lob @Column(name = "comment_text") public String comment;
    /** Se invalida si la solicitud se vuelve a editar. */
    @Convert(converter = NumericBooleanConverter.class) public boolean active = true;
    /** Sello HMAC del SQL aprobado y la base destino. */
    @Column(name = "content_hash") public String contentHash;
    @ManyToOne @JoinColumn(name = "key_id") public DbaKey key;
    /** Firma WebAuthn del DBA sobre la decisión (JSON). */
    @Lob public String signature;
    @Column(name = "decided_at") public OffsetDateTime decidedAt = OffsetDateTime.now();
}
