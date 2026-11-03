package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/** Llave de seguridad (WebAuthn/FIDO2) de un DBA. Solo se guarda la llave pública. */
@Entity
@Table(name = "dba_keys")
public class DbaKey extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String username;
    public String label;
    @Column(name = "credential_id") public String credentialId;
    /** AttestedCredentialData (incluye la llave pública COSE), en base64. */
    @Lob @Column(name = "credential_data") public String credentialData;
    /** SHA-256 de la llave pública. */
    public String fingerprint;
    @Column(name = "sign_count") public long signCount;
    /** PENDING (espera aval), ACTIVE, REVOKED. */
    public String status;
    @Convert(converter = NumericBooleanConverter.class) @Column(name = "is_root") public boolean root;
    @Column(name = "rp_id") public String rpId;
    public String origin;
    @ManyToOne @JoinColumn(name = "endorsed_by") public DbaKey endorsedBy;
    @Column(name = "endorsed_by_user") public String endorsedByUser;
    /** Firma (aserción WebAuthn) con la que la llave que avala aceptó esta llave. */
    @Lob public String endorsement;
    @Column(name = "created_at") public OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "activated_at") public OffsetDateTime activatedAt;
    @Column(name = "last_used_at") public OffsetDateTime lastUsedAt;
    @Column(name = "revoked_at") public OffsetDateTime revokedAt;
    @Column(name = "revoked_by") public String revokedBy;
}
