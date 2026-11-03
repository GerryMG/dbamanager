package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/** Usuarios vistos por OIDC (la fuente de verdad es Keycloak). */
@Entity
@Table(name = "app_users")
public class AppUser extends PanacheEntityBase {
    @Id public String username;
    public String sub;
    @Column(name = "full_name") public String fullName;
    public String email;
    public String roles;
    @Column(name = "last_seen") public OffsetDateTime lastSeen;
}
