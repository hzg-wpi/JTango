/**
 * Copyright (C) :     2012
 *
 * 	Synchrotron Soleil
 * 	L'Orme des merisiers
 * 	Saint Aubin
 * 	BP48
 * 	91192 GIF-SUR-YVETTE CEDEX
 *
 * This file is part of Tango.
 *
 * Tango is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tango is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Tango.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.tango.server;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;
import org.tango.client.database.DatabaseFactory;
import org.tango.logging.LoggingManager;
import org.tango.orb.ORBManager;
import org.tango.server.annotation.Device;
import org.tango.server.export.TangoExporter;
import org.tango.server.servant.Constants;
import org.tango.utils.DevFailedUtils;

import fr.esrf.Tango.DevFailed;

/**
 * Manage a tango server.
 * 
 * @author ABEILLE
 * 
 */
public final class ServerManager {
    private static final String SERVER_NAME_LOGGING = "serverName";
    private static final String NODB = "-nodb";
    /**
     * maximun length for device server name (255 characters)
     */
    public static final int SERVER_NAME_MAX_LENGTH = 255;
    private static final String INIT_ERROR = "INIT_ERROR";
    private final Logger logger = LoggerFactory.getLogger(ServerManager.class);
    private final XLogger xlogger = XLoggerFactory.getXLogger(ServerManager.class);
    private boolean useDb;
    private final AtomicBoolean isStarted = new AtomicBoolean();

    private static final ServerManager INSTANCE = new ServerManager();
    /**
     * The name of the executable
     */
    private String execName;
    /**
     * The name of the instance
     */
    private String instanceName;
    /**
     * execName/instanceName
     */
    private String serverName;
    private String hostName;
    private String pid = "0";

    private final Map<String, Class<?>> tangoClasses = new HashMap<String, Class<?>>();

    private TangoExporter tangoExporter;

    private ServerManager() {
	Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
	    /**
	     * shutdown hook
	     */
	    public void run() {
		MDC.put(SERVER_NAME_LOGGING, serverName);
		logger.error("Shutdown hook unregister " + serverName);
		try {
		    ServerManager.getInstance().stop();
		} catch (final DevFailed e) {
		}
	    }
	}));
    }

    /**
     * Get a ServerManager
     * 
     * @return the server manager
     */
    public static ServerManager getInstance() {
	return INSTANCE;
    }

    /**
     * Add a class to the server.
     * 
     * @param tangoClass
     *            The class name as defined in the tango database
     * @param deviceClass
     *            The class that define a device with {@link Device}
     */
    public void addClass(final String tangoClass, final Class<?> deviceClass) {
	tangoClasses.put(tangoClass, deviceClass);
    }

    /**
     * Starts a Tango server. The system property TANGO_HOST is mandatory if using the tango database. If the tango db
     * is not used, the system property OAPort(for jacorb) must be set. The errors occurred will be only logged.
     * 
     * <pre>
     * ServerManager.getInstance().addClass(JTangoTest.class.getCanonicalName(), JTangoTest.class);
     * ServerManager.getInstance().start(new String[] { &quot;1&quot; }, &quot;JTangoTest&quot;);
     * </pre>
     * 
     * @param args
     *            The arguments to pass. instanceName [-v[trace level]] [-nodb [-dlist <device name list>]]
     * @param execName
     *            The name of the server as defined by Tango.
     * @see ServerManager#addClass(String, Class)
     */
    public synchronized void start(final String[] args, final String execName) {
	if (!isStarted.get()) {
	    try {
		init(args, execName);
	    } catch (final DevFailed e) {
		DevFailedUtils.printDevFailed(e);
	    }
	}
    }

    /**
     * Idem as start but throw exceptions. @see ServerManager#start(String[], String)
     * 
     * @param args
     * @param execName
     * @throws DevFailed
     */
    public synchronized void startError(final String[] args, final String execName) throws DevFailed {
	if (isStarted.get()) {
	    DevFailedUtils.throwDevFailed("this server is already started");
	}
	init(args, execName);
    }

    /**
     * Starts a Tango server. The system property TANGO_HOST is mandatory if using the tango database. If the tango db
     * is not used the system property OAPort(for jacorb) must be set. The errors occurred will be only logged.
     * 
     * <pre>
     * ServerManager.getInstance().start(new String[] { &quot;1&quot; }, JTangoTest.class);
     * </pre>
     * 
     * @param args
     *            The arguments to pass. instanceName [-v[trace level]] [-nodb [-dlist <device name list>]]
     * @param deviceClass
     *            The class of the device. The server name and class name must be defined in tango db with
     *            deviceClass.getSimpleName to be started with this method.
     * @see ServerManager#addClass(String, Class)
     */
    public synchronized void start(final String[] args, final Class<?> deviceClass) {
	if (!isStarted.get()) {
	    addClass(deviceClass.getSimpleName(), deviceClass);
	    try {

		init(args, deviceClass.getSimpleName());

	    } catch (final DevFailed e) {
		DevFailedUtils.printDevFailed(e);
	    }
	}
    }

    private void init(final String[] args, final String execName) throws DevFailed {
	xlogger.entry();
	// assume SLF4J is bound to logback in the current environment
	// final LoggerContext lc = (LoggerContext)
	// LoggerFactory.getILoggerFactory();
	// // print logback's internal status
	// StatusPrinter.print(lc);

	this.execName = execName;
	// Manage command line option
	checkArgs(args);
	serverName = this.execName + "/" + instanceName;
	MDC.put(SERVER_NAME_LOGGING, serverName);
	logger.info("Starting server {}", serverName);

	final StringBuilder tmp = new StringBuilder(serverName);

	// Check that the server name is not too long
	if (tmp.length() > SERVER_NAME_MAX_LENGTH) {
	    DevFailedUtils.throwDevFailed(INIT_ERROR, "The device server name is too long! Max length is "
		    + SERVER_NAME_MAX_LENGTH + " characters.");
	}
	isStarted.set(true);
	initPIDAndHostName();
	final String toBeImported = Constants.ADMIN_DEVICE_DOMAIN + "/" + serverName;
	ORBManager.init(useDb, toBeImported);
	tangoExporter = new TangoExporter(hostName, serverName, pid, tangoClasses);
	tangoExporter.exportAll();
	logger.info("TANGO server {} started", serverName);
	// start the ORB
	ORBManager.startDetached();
	xlogger.exit();
    }

    /**
     * Stop the server and clear all
     * 
     * @throws DevFailed
     */
    public void stop() throws DevFailed {
	try {
	    if (isStarted.get()) {
		tangoClasses.clear();
		tangoExporter.clearClass();
		tangoExporter.unexportAll();
	    }
	} finally {
	    ORBManager.shutdown();
	    logger.error("everything has been shutdown normally");
	    isStarted.set(false);
	}
    }

    /**
     * Get main argurments
     * 
     * @return The usage
     */
    private String getUsage() {
	return "usage : java -DTANGO_HOST=$TANGO_HOST " + execName
		+ " instance_name [-v[trace level]]  [-nodb [-dlist <device name list>] [-file=fileName]]";
    }

    /**
     * Check the command line arguments. The first one is mandatory and is the server name. A -v option is authorized
     * with an optional argument.
     * 
     * @param argv
     * @throws DevFailed
     */
    private void checkArgs(final String[] argv) throws DevFailed {
	if (argv.length < 1) {
	    DevFailedUtils.throwDevFailed(INIT_ERROR, getUsage());
	}
	instanceName = argv[0];
	useDb = true;
	DatabaseFactory.setUseDb(true);
	List<String> noDbDevices = new ArrayList<String>();
	for (int i = 1; i < argv.length; i++) {
	    final String arg = argv[i];
	    if (arg.startsWith("-h")) { // trace instance name
		System.out.println("instance list for server " + execName + ": "
			+ Arrays.toString(DatabaseFactory.getDatabase().getInstanceNameList(execName)));
	    } else if (arg.startsWith("-v")) { // logging level
		final int level = Integer.parseInt(arg.substring(arg.lastIndexOf('v') + 1));
		LoggingManager.getInstance().setRootLoggingLevel(level);
	    } else if (arg.startsWith("-dlist")) {
		noDbDevices = configureNoDB(argv, i);
		useDb = false;
	    } else if (arg.startsWith("-file")) {
		configureNoDBFile(argv, arg, noDbDevices);
		useDb = false;
	    }
	}
    }

    /**
     * Configure {@link DatabaseFactory} without a tango db
     * 
     * @param argv
     * @param currentIdx
     * @return The list of no db devices
     * @throws DevFailed
     */
    private List<String> configureNoDB(final String argv[], final int currentIdx) throws DevFailed {
	final List<String> noDbDevices = new ArrayList<String>();
	if (!ArrayUtils.contains(argv, NODB)) {
	    DevFailedUtils.throwDevFailed(INIT_ERROR, getUsage());
	} else {
	    for (int j = currentIdx + 1; j < argv.length; j++) {
		if (!argv[j].startsWith("-")) {
		    noDbDevices.add(argv[j]);
		    logger.warn("Device with no db: " + argv[j]);
		} else {
		    break;
		}
	    }
	    DatabaseFactory.setNoDbDevices(noDbDevices.toArray(new String[noDbDevices.size()]), tangoClasses.keySet()
		    .toArray(new String[tangoClasses.size()]));
	}
	return noDbDevices;

    }

    /**
     * Configure {@link DatabaseFactory} without a tango db and a file for properties
     * 
     * @param argv
     * @param arg
     * @param noDbDevices
     * @throws DevFailed
     */
    private void configureNoDBFile(final String argv[], final String arg, final List<String> noDbDevices)
	    throws DevFailed {
	if (!ArrayUtils.contains(argv, NODB)) {
	    DevFailedUtils.throwDevFailed(INIT_ERROR, getUsage());
	} else {
	    final String name = arg.split("=")[1];
	    final File file = new File(name);
	    if (!file.exists() && !file.isFile()) {
		DevFailedUtils.throwDevFailed(INIT_ERROR, name + " does not exists or is not a file");
	    }
	    logger.warn("Tango Database is not used - with file {} ", file.getPath());
	    DatabaseFactory.setDbFile(file, noDbDevices.toArray(new String[noDbDevices.size()]), tangoClasses.keySet()
		    .toArray(new String[tangoClasses.size()]));
	}

    }

    /**
     * WARNING: it is jvm dependent (works for sun')
     * 
     * @throws DevFailed
     */
    private void initPIDAndHostName() throws DevFailed {

	final RuntimeMXBean rmxb = ManagementFactory.getRuntimeMXBean();
	final String pidAndHost = rmxb.getName();
	final String[] splitted = pidAndHost.split("@");
	if (splitted.length > 1) {
	    pid = splitted[0];
	}
	// hostName = splitted[1];
	try {
	    final InetAddress addr = InetAddress.getLocalHost();
	    hostName = addr.getCanonicalHostName();
	} catch (final UnknownHostException e) {
	    DevFailedUtils.throwDevFailed(e);
	}

	logger.debug("pid: " + pid);
	logger.debug("hostName: " + hostName);

    }

    /**
     * The host on which this server is running
     * 
     * @return the host name
     */
    public String getHostName() {
	return hostName;
    }

    /**
     * The pid of this server
     * 
     * @return the pid
     */
    public String getPid() {
	return pid;
    }

    /**
     * execName/instanceName
     * 
     * @return execName/instanceName
     */
    public String getExecName() {
	return execName;
    }

    /**
     * The instance name
     * 
     * @return The instance name
     */
    public String getInstanceName() {
	return instanceName;
    }

    /**
     * The server name
     * 
     * @return The server name
     */
    public String getServerName() {
	return serverName;
    }

    /**
     * Get the started devices of this server. WARNING: result is filled after server has been started
     * 
     * @param tangoClass
     * @return The devices
     * @throws DevFailed
     */
    public String[] getDevicesOfClass(final String tangoClass) throws DevFailed {
	return tangoExporter.getDevicesOfClass(tangoClass);
    }

    public String getAdminDeviceName() {
	return Constants.ADMIN_DEVICE_DOMAIN + "/" + serverName;
    }

    /**
     * 
     * @return true is the server is running
     */
    public boolean isStarted() {
	return isStarted.get();
    }

}