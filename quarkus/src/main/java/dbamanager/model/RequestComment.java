package dbamanager.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.type.NumericBooleanConverter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "request_comments")
public class RequestComment extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "request_id") public ChangeRequest request;
    public String author;
    @Lob public String body;
    @Column(name = "created_at") public OffsetDateTime createdAt = OffsetDateTime.now();
}
