package info.novatec.inspectit.agent.sensor.jmx;

import info.novatec.inspectit.agent.config.IConfigurationStorage;
import info.novatec.inspectit.agent.config.impl.JmxSensorConfig;
import info.novatec.inspectit.agent.config.impl.JmxSensorTypeConfig;
import info.novatec.inspectit.agent.config.impl.UnregisteredJmxConfig;
import info.novatec.inspectit.agent.core.ICoreService;
import info.novatec.inspectit.agent.core.IIdManager;
import info.novatec.inspectit.agent.core.IdNotAvailableException;
import info.novatec.inspectit.communication.data.JmxSensorValueData;
import info.novatec.inspectit.spring.logger.Log;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.slf4j.Logger;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The implementation of the JmxSensor.
 * 
 * @author Alfred Krauss
 * @author Marius Oehler
 * 
 */
public class JmxSensor implements IJmxSensor, InitializingBean {

	/**
	 * Defines the interval of the maximum call rate of the
	 * {@link JmxSensor#collectData(ICoreService, long)} method.
	 */
	private static final int DATA_COLLECT_INTERVAL = 5000;

	/**
	 * Defines the interval of the maximum call rate of the {@link #registerMBeans()} method.
	 */
	private static final int REGISTER_BEAN_INTERVAL = 60000;

	/**
	 * The logger of the class.
	 */
	@Log
	Logger log;

	/**
	 * The instance of the configuration storage.
	 */
	@Autowired
	IConfigurationStorage configurationStorage;

	/**
	 * The ID Manager used to get the correct IDs.
	 */
	@Autowired
	IIdManager idManager;

	/**
	 * The MBeanServer providing information about registered MBeans.
	 */
	MBeanServer mBeanServer;

	/**
	 * List of unregistered JmxConfigs.
	 */
	List<UnregisteredJmxConfig> unregisteredJmxConfigs = new ArrayList<UnregisteredJmxConfig>();

	/**
	 * Map of registeredJmxSensorConfigs. Name of the, in the config specified, attribute is the
	 * key.
	 */
	Map<String, JmxSensorConfig> registeredJmxSensorConfigs = new HashMap<String, JmxSensorConfig>();

	/**
	 * Map used to connect the ObjectName of a MBean with the string-representation of the same
	 * MBean. Recreation of the ObjectName is no longer necessary for the update-method.
	 */
	Map<String, ObjectName> nameStringToObjectName = new HashMap<String, ObjectName>();

	/**
	 * The timestamp of the last {@link #collectData(ICoreService, long)} method invocation.
	 */
	private long lastDataCollectionTimestamp = 0;

	/**
	 * The timestamp of the last {@link ##registerMBeans()} method invocation.
	 */
	private long lastRegisterBeanTimestamp = 0;

	/**
	 * Map of active attributes.
	 */
	Map<String, JmxSensorConfig> activeAttributes = new HashMap<String, JmxSensorConfig>();

	/**
	 * {@inheritDoc}
	 */
	public void init(Map<String, Object> parameter) {
		unregisteredJmxConfigs.addAll(configurationStorage.getUnregisteredJmxConfigs());
		mBeanServer = ManagementFactory.getPlatformMBeanServer();
	}

	/**
	 * {@inheritDoc}
	 */
	public void update(ICoreService coreService, long sensorTypeIdent) {
		long currentTime = System.currentTimeMillis();

		// Check if the registerMBeans method should be invoked
		if (currentTime - lastRegisterBeanTimestamp > REGISTER_BEAN_INTERVAL) {
			// set the invocation timestamp
			lastRegisterBeanTimestamp = System.currentTimeMillis();

			// Add and sort configs according to given params for objectname and attributenames
			registerMBeans();
		}

		// Check if the collectData method should be invoked
		if (currentTime - lastDataCollectionTimestamp > DATA_COLLECT_INTERVAL) {
			// store the invocation timestamp
			lastDataCollectionTimestamp = System.currentTimeMillis();

			collectData(coreService, sensorTypeIdent);
		}
	}

	/**
	 * Collects the data and sends it to the CMR.
	 * 
	 * @param coreService
	 *            The core service which is needed to store the measurements to.
	 * @param sensorTypeIdent
	 *            The ID of the sensor type so that old data can be found. (for aggregating etc.)
	 */
	void collectData(ICoreService coreService, long sensorTypeIdent) {
		Timestamp timestamp = new Timestamp(Calendar.getInstance().getTime().getTime());

		for (Iterator<Entry<String, JmxSensorConfig>> iterator = activeAttributes.entrySet().iterator(); iterator.hasNext();) {
			JmxSensorConfig jsc = iterator.next().getValue();

			try {
				// Retrieving the value of the, in the JmxSensorConfig specified, MBeanAttribute
				ObjectName objectName = nameStringToObjectName.get(jsc.getmBeanObjectName());
				Object collectedValue = mBeanServer.getAttribute(objectName, jsc.getAttributeName());

				String value;
				if (collectedValue.getClass().isArray()) {
					value = Arrays.toString((Object[]) collectedValue);
				} else {
					value = collectedValue.toString();
				}

				// Create a new JmxSensorValueData to be saved into the database
				long platformid = idManager.getPlatformId();
				long sensorTypeId = idManager.getRegisteredSensorTypeId(sensorTypeIdent);
				long registeredmBeanId = idManager.getRegisteredmBeanId(jsc.getId());

				JmxSensorValueData jsvd = new JmxSensorValueData(registeredmBeanId, value, timestamp, platformid, sensorTypeId);

				coreService.addJmxSensorValueData(sensorTypeIdent, jsc.getmBeanObjectName(), jsc.getAttributeName(), jsvd);
			} catch (AttributeNotFoundException e) {
				iterator.remove();
				log.warn("JMX::AttributeNotFound. Attribute was not found. Maybe currently not available on the server. Attribute removed from the actively read list.", e);
			} catch (InstanceNotFoundException e) {
				iterator.remove();
				log.warn("JMX::Instance not found. MBean may not be registered on the Server. Attribute removed from the actively read list.", e);
			} catch (MBeanException e) {
				iterator.remove();
				log.warn("JMX::MBean. Undefined problem with the MBean. Attribute removed from the actively read list.", e);
			} catch (ReflectionException e) {
				iterator.remove();
				log.warn("JMX::Reflection error. MBean may not be registered on the Server. Attribute removed from the actively read list.", e);
			} catch (IdNotAvailableException e) {
				if (log.isDebugEnabled()) {
					log.debug("JMX::IdNotAvailable. MBean may not be registered on the Server.", e);
				}
			}
		}
	}

	/**
	 * Registers a new MBean on the first appearance.
	 */
	void registerMBeans() {
		for (Iterator<UnregisteredJmxConfig> iterator = unregisteredJmxConfigs.iterator(); iterator.hasNext();) {

			UnregisteredJmxConfig ujc = iterator.next();

			String objectNameExpression = ujc.getPassedObjectNameExpression();
			String attributeNameExpression = ujc.getPassedAttributeNameExpression();
			try {
				Set<ObjectName> fittingMBeans = mBeanServer.queryNames(new ObjectName(objectNameExpression), null);

				for (ObjectName objectName : fittingMBeans) {
					String mBeanAttributeKey = objectName + attributeNameExpression;
					if (!registeredJmxSensorConfigs.containsKey(mBeanAttributeKey)) {

						MBeanAttributeInfo[] attributes = mBeanServer.getMBeanInfo(objectName).getAttributes();

						for (MBeanAttributeInfo mBeanAttributeInfo : attributes) {
							if (mBeanAttributeInfo.getName().equals(attributeNameExpression)) {
								JmxSensorConfig jsc = new JmxSensorConfig();
								jsc.setJmxSensorTypeConfig(ujc.getJmxSensorTypeConfig());
								jsc.setmBeanObjectName(objectName.toString());
								jsc.setAttributeName(mBeanAttributeInfo.getName());
								jsc.setmBeanAttributeDescription(mBeanAttributeInfo.getDescription());
								jsc.setmBeanAttributeIsIs(mBeanAttributeInfo.isIs());
								jsc.setmBeanAttributeIsReadable(mBeanAttributeInfo.isReadable());
								jsc.setmBeanAttributeIsWritable(mBeanAttributeInfo.isWritable());
								jsc.setmBeanAttributeType(mBeanAttributeInfo.getType());

								idManager.registerJmxSensorConfig(jsc);
								registeredJmxSensorConfigs.put(mBeanAttributeKey, jsc);
								activeAttributes.put(mBeanAttributeKey, jsc);
								nameStringToObjectName.put(objectName.toString(), objectName);
								break;
							}
						}
					} else if (!activeAttributes.containsKey(mBeanAttributeKey)) {
						MBeanAttributeInfo[] attributes = mBeanServer.getMBeanInfo(objectName).getAttributes();
						for (MBeanAttributeInfo mBeanAttributeInfo : attributes) {
							if (mBeanAttributeInfo.getName().equals(attributeNameExpression)) {
								// reactivates an attribute to prevent re-occurring exceptions if a
								// mBean is removed during runtime.
								activeAttributes.put(mBeanAttributeKey, registeredJmxSensorConfigs.get(mBeanAttributeKey));
								break;
							}
						}
					}
				}
			} catch (MalformedObjectNameException e) {
				// Removes a mBean from the configs due to incorrect definition of its name and
				// therefore re-occurring exceptions.
				iterator.remove();
				log.warn("JMX::MalformedObjectName. '" + objectNameExpression + "' may not be registered on the Server. Removed from List.", e);
			} catch (IntrospectionException e) {
				log.warn("JMX::Introspection failure. MBean may not be registered on the Server.", e);
			} catch (InstanceNotFoundException e) {
				log.warn("JMX::Instance not found. MBean may not be registered on the Server.", e);
			} catch (ReflectionException e) {
				log.warn("JMX::Reflection error. MBean may not be registered on the Server.", e);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void afterPropertiesSet() throws Exception {
		for (JmxSensorTypeConfig config : configurationStorage.getJmxSensorTypes()) {
			if (config.getClassName().equals(this.getClass().getName())) {
				this.init(config.getParameters());
				config.setSensorType(this);
				break;
			}
		}
	}
}