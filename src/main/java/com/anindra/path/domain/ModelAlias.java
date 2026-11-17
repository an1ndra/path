package com.anindra.path.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * An operator-defined alias: a stable, ordered chain of public model ids. Unlike the
 * capability aliases, which are recomputed from prices on every request, this one keeps
 * meaning what it meant when it was created.
 */
@Entity
@Table(name = "model_aliases")
@Getter
@Setter
@NoArgsConstructor
public class ModelAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String alias;

    /** Comma-separated public model ids, in fallback order. */
    @Column(nullable = false, length = 1000)
    private String models;

    @Column(length = 400)
    private String description;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public ModelAlias(String alias, List<String> models, String description) {
        this.alias = alias;
        setModelList(models);
        this.description = description;
    }

    public List<String> modelList() {
        return Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setModelList(List<String> values) {
        this.models = String.join(",", values);
    }
}
