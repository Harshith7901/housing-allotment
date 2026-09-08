package dev.harshith.housing.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A published rule set, stored as its canonical text encoding plus its hash.
 *
 * <p>Stored as text rather than as normalised quota rows on purpose. The bytes in
 * {@code encoded} are the exact bytes that {@code ruleSetHash} is the digest of and that
 * appear in the published verification bundle. Normalising the quotas into tables would
 * mean the hash is over a re-serialisation, and any change to that serialiser — a column
 * added, a default changed — would silently invalidate the hash of every historical rule
 * set. The text is the record; the parsed form is derived from it on read.
 *
 * <p>There is no update path. Amending the rules means publishing a new version.
 */
@Entity
@Table(name = "rule_set")
@Getter
@Setter
@NoArgsConstructor
public class RuleSetEntity {

    @Id
    @Column(length = 64)
    private String version;

    @Column(nullable = false, length = 32)
    private String schemeCode;

    @Column(nullable = false, length = 200_000)
    private String encoded;

    @Column(nullable = false, length = 64)
    private String ruleSetHash;

    @Column(nullable = false)
    private Instant publishedAt;

    @Column(nullable = false, length = 128)
    private String publishedBy;

    @Column(length = 500)
    private String publishedRulesUri;
}
