package io.cloudsoft.socialapps.wordpress;


import static brooklyn.entity.basic.lifecycle.CommonCommands.installPackage;
import static brooklyn.entity.basic.lifecycle.CommonCommands.alternatives;
import static brooklyn.entity.basic.lifecycle.CommonCommands.sudo;
import static brooklyn.entity.basic.lifecycle.CommonCommands.ok;
import static com.google.common.collect.ImmutableMap.of;
import static java.lang.String.format;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import brooklyn.entity.basic.AbstractSoftwareProcessSshDriver;
import brooklyn.entity.basic.lifecycle.CommonCommands;
import brooklyn.entity.drivers.downloads.DownloadResolver;
import brooklyn.location.basic.SshMachineLocation;

public class WordpressSshDriver extends AbstractSoftwareProcessSshDriver implements WordpressDriver {

    // FIXME Only tested on CentOS
    // e.g. on debian, need to use /etc/init.d/apache2 instead of /etc/init.d/httpd
    
    // FIXME Some good security advice which we should follow at:
    // http://jeffreifman.com/detailed-wordpress-guide-for-aws/install-wordpress/
    // Also look at DrupalSshDriver. It does some good `chown`, `chgrp` and `chmod`.
    
    // FIXME There are other commands at:
    // http://jeffreifman.com/detailed-wordpress-guide-for-aws/install-wordpress/
    // to do with /etc/apache2/sites-available, `a2ensite wordpress` etc; not doing those currently.
    
    // FIXME Haven't set up email-server, so admin e-mails are not sent
    // See debian-specific postfix setup in DrupalSshDriver.
    
    private String expandedInstallDir;

	public WordpressSshDriver(WordpressImpl entity, SshMachineLocation machine) {
        super(entity, machine);
    }

	@Override
	public WordpressImpl getEntity() {
	    return (WordpressImpl) super.getEntity();
	}
	
	protected String getExpandedInstallDir() {
		return expandedInstallDir;
	}
	
	protected String getWwwDir() {
	    return "/var/www/html";
	}
	
    @Override
    public void install() {
        DownloadResolver resolver = entity.getManagementContext().getEntityDownloadsManager().newDownloader(this);
        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        expandedInstallDir = getInstallDir()+"/"+resolver.getUnpackedDirectoryName("wordpress");
        
        List<String> commands = new LinkedList<String>();
        
        commands.add(installPackage(of("yum", "httpd", "apt", "apache2"), null));
        commands.add(alternatives(Arrays.asList(
                installPackage(of("yum", "php53", "apt", "php5"), null),
                installPackage("php")), "php/php53 not available"));
        commands.add(alternatives(Arrays.asList(
                installPackage(of("yum", "php53-mysql", "apt", "php5-mysql"), null),
                installPackage("php-mysql")), "php/php53 mysql not available"));
        commands.add(alternatives(Arrays.asList(
                installPackage(of("yum", "php53-gd", "apt", "php5-gd"), null),
                installPackage("php-gd")), "php/php53 gd not available"));
        commands.add(installPackage(of("apt", "libapache2-mod-php5"), null));
        commands.add(installPackage(of("apt", "libapache2-mod-auth-mysql"), null));
        
        // as per willomitzer comment at http://googolflex.com/?p=482 (only needed if selinux is on this box)
        commands.add(ok(sudo("setsebool -P httpd_can_network_connect 1")));
        
        commands.addAll(CommonCommands.downloadUrlAs(urls, saveAs));
        commands.add(CommonCommands.INSTALL_TAR);
        commands.add("tar xzfv " + saveAs);

        newScript(INSTALLING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void customize() {
        // this call allows it to wait for the database startup to complete, if configured with:
        //     .configure(Wordpress.DATABASE_HOSTNAME, DependentConfiguration.attributeWhenReady(mySqlNode, MySqlNode.HOSTNAME))
        log.info("Waiting till the database is up");
        entity.getConfig(Wordpress.DATABASE_UP);
        log.info("Database is up and running");

        String configFileContents = processTemplate(getEntity().getTemplateConfigurationUrl());
        String destinationConfigFile = format("/tmp/wp-config.php", getWwwDir());
        getMachine().copyTo(new ByteArrayInputStream(configFileContents.getBytes()), destinationConfigFile);
        
        String customInstallFileContents = processTemplate(getEntity().getTemplateCustomInstallPhpUrl());
        String destinationCustomInstallFile = format("/tmp/custom-install.php", getWwwDir());
        getMachine().copyTo(new ByteArrayInputStream(customInstallFileContents.getBytes()), destinationCustomInstallFile);

        List<String> commands = new LinkedList<String>();
        commands.add(sudo(format("mkdir -p %s", getWwwDir())));
        commands.add(sudo(format("cp -R %s/* %s/", getExpandedInstallDir(), getWwwDir())));
        commands.add(format("cd %s", getWwwDir()));
        commands.add(sudo("cp /tmp/wp-config.php ."));
        commands.add(sudo("cp /tmp/custom-install.php ."));
        commands.add(format("php custom-install.php"));

        // Get metrics from apache httpd
        // Note ugliness of sed+cp is because wrapping `sed -i.bak '...'` in `sudo -E -n -s --` 
        // breaks on CentOS, because of quotes.
        String httpdConfFile = "/etc/httpd/conf/httpd.conf";
        String httpdConfTempFile = "/tmp/httpd.conf-"+entity.getId();
        String serverStatusConf = 
                "<Location /server-status>\n" +
                "SetHandler server-status\n" +
                "Order Deny,Allow\n" +
                "Deny from all\n" +
                "Allow from localhost\n" +
                "</Location>\n";
        commands.add(format("sed 's/^#ExtendedStatus On/ExtendedStatus On/' %s > %s", httpdConfFile, httpdConfTempFile));
        
        // TODO Don't add this multiple times!
        // if [ \"`grep -E \"^SetHandler server-status\" "+httpdConfTempFile+"`\" == \"\" ]; then\n"
        commands.add("cat >> "+httpdConfTempFile+" << END_CONF_"+entity.getId()+"\n" +
        		serverStatusConf+"\n" +
				"END_CONF_"+entity.getId()+"\n");
        
        commands.add(sudo(format("cp %s %s", httpdConfTempFile, httpdConfFile)));
        
        newScript(CUSTOMIZING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public void launch() {
        List<String> commands = new LinkedList<String>();
        commands.add(sudo("/etc/init.d/httpd stop"));
        commands.add(sudo("/etc/init.d/httpd start"));
        
        newScript(LAUNCHING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }

    @Override
    public boolean isRunning() {
        List<String> commands = new LinkedList<String>();
        commands.add(sudo("/etc/init.d/httpd status"));

        return newScript(CHECK_RUNNING).
                body.append(commands).execute() == 0;
    }

    @Override
    public void stop() {
        List<String> commands = new LinkedList<String>();
        commands.add(sudo("/etc/init.d/httpd stop"));

        newScript(STOPPING).
                failOnNonZeroResultCode().
                body.append(commands).execute();
    }
}
