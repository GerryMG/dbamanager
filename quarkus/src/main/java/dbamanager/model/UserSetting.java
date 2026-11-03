package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

/** Preferencias que solo el propio usuario puede cambiar (no hay API para cambiarlas a otro). */
@Entity
@Table(name = "user_settings")
public class UserSetting extends PanacheEntityBase {
    @Id public String username;
    @Convert(converter = NumericBooleanConverter.class) @Column(name = "can_execute") public boolean canExecute;
    @Column(name = "can_execute_changed_at") public OffsetDateTime canExecuteChangedAt;
    @Column(name = "can_execute_until") public OffsetDateTime canExecuteUntil;
    /** Último inicio de sesión con contraseña (reautenticación) registrado por /reauth. */
    @Column(name = "reauth_at") public OffsetDateTime reauthAt;
}
