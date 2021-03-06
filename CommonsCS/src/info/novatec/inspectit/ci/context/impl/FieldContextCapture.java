package info.novatec.inspectit.ci.context.impl;

import info.novatec.inspectit.ci.context.AbstractContextCapture;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlRootElement;

import org.apache.commons.collections.CollectionUtils;

/**
 * {@link AbstractContextCapture} for fields. Saves field name to capture.
 * 
 * @author Ivan Senic
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "field-capture")
public class FieldContextCapture extends AbstractContextCapture {

	/**
	 * Name of the field to catch.
	 */
	@XmlAttribute(name = "field-name")
	private String fieldName;

	/**
	 * {@inheritDoc}
	 */
	@Override
	public String getAgentStringNotation() {
		StringBuffer stringBuffer = new StringBuffer("f=");
		stringBuffer.append(getDisplayName());
		stringBuffer.append(';');
		stringBuffer.append(fieldName);
		if (CollectionUtils.isNotEmpty(getPaths())) {
			for (String path : getPaths()) {
				stringBuffer.append('.');
				stringBuffer.append(path);
			}
		}
		return stringBuffer.toString();
	}

	/**
	 * Gets {@link #fieldName}.
	 * 
	 * @return {@link #fieldName}
	 */
	public String getFieldName() {
		return fieldName;
	}

	/**
	 * Sets {@link #fieldName}.
	 * 
	 * @param fieldName
	 *            New value for {@link #fieldName}
	 */
	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((fieldName == null) ? 0 : fieldName.hashCode());
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!super.equals(obj)) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		FieldContextCapture other = (FieldContextCapture) obj;
		if (fieldName == null) {
			if (other.fieldName != null) {
				return false;
			}
		} else if (!fieldName.equals(other.fieldName)) {
			return false;
		}
		return true;
	}

}
