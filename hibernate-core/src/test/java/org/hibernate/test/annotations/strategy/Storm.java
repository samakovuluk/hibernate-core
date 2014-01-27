//$Id$
package org.hibernate.test.annotations.strategy;
import javax.persistence.Column;
import javax.persistence.Embedded;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import org.hibernate.annotations.Index;
import org.hibernate.testing.FailureExpectedWithNewMetamodel;

/**
 * @author Emmanuel Bernard
 */
@Entity
@FailureExpectedWithNewMetamodel
@Table(uniqueConstraints = {@UniqueConstraint(columnNames = {"start.country", "start.city"})})
public class Storm {
	private Integer id;
	private Location start;
	private Location end;
	private String stormName;

	@Id
	@GeneratedValue
	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	@Embedded
	public Location getStart() {
		return start;
	}

	public void setStart(Location start) {
		this.start = start;
	}

	@Embedded
	public Location getEnd() {
		return end;
	}

	public void setEnd(Location end) {
		this.end = end;
	}

	@Index(name="storm_name_idx")
	@Column(unique = true)
	public String getStormName() {
		return stormName;
	}

	public void setStormName(String name) {
		this.stormName = name;
	}
}
