package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "projects")
public class Project extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String code;
    public String name;
    public String description;
    public String owner;
    @Convert(converter = NumericBooleanConverter.class) public boolean active = true;
    @Column(name = "created_at") public OffsetDateTime createdAt = OffsetDateTime.now();
}
