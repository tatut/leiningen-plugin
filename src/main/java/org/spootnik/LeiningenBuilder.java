package org.spootnik;
import hudson.EnvVars;
import hudson.Launcher;
import hudson.FilePath;
import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import hudson.console.ConsoleNote;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.ArgumentListBuilder;
import hudson.model.Result;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;

import java.io.IOException;
import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jenkins.util.BuildListenerAdapter;


/**
 * Sample {@link Builder}.
 *
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked
 * and a new {@link LeiningenBuilder} is created. The created
 * instance is persisted to the project configuration XML by using
 * XStream, so this allows you to use instance fields (like {@link #name})
 * to remember the configuration.
 *
 * <p>
 * When a build is performed, the {@link #perform(AbstractBuild, Launcher, BuildListener)}
 * method will be invoked.
 *
 * @author Kohsuke Kawaguchi
 */
public class LeiningenBuilder extends Builder {

	private final String task;
	private String subdirPath;
	private String jvmOpts;
	private boolean parallel;

	// Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
	@DataBoundConstructor
	public LeiningenBuilder(String task, String subdirPath, String jvmOpts, boolean parallel) {
		this.task = task;
		this.subdirPath = subdirPath;
		this.jvmOpts = jvmOpts;
		this.parallel = parallel;
	}


	public boolean isParallel() {
		return parallel;
	}


	/**
	 * We'll use this from the <tt>config.jelly</tt>.
	 */
	public String getTask() {
		return task;
	}

	public String getSubdirPath() {
		return subdirPath;
	}

	public String getJvmOpts() {
		return jvmOpts;
	}

	
	Map<String,List<String>> parseLeinTasks(String tasks) {
		HashMap<String,List<String>> taskDeps = new HashMap<>();
		
		Arrays.asList(tasks.split("\n")).stream().forEach( (task) -> {
			String[] taskAndDeps = task.split(":");
			if(taskAndDeps.length > 2) {
				throw new IllegalArgumentException("Expected task line to be: \"task: deps\"");
			}
			List<String> deps = taskAndDeps.length < 2 
					? Collections.emptyList()
					: Arrays.asList(taskAndDeps[1].split(";"))
						.stream().map(d -> d.trim()).collect(Collectors.toList());
			taskDeps.put(taskAndDeps[0].trim(), deps);
		});
		
		return taskDeps;
	}
	
	enum Status { PENDING, RUNNING, COMPLETE, FAILED };
	
	public boolean perform(final AbstractBuild build, final Launcher launcher, final BuildListener listener) {

		if(parallel) {
			// Run lein tasks parallel in dependency order
			final PrintStream log = listener.getLogger();

			Map<String,List<String>> taskDeps = parseLeinTasks(this.task);
			Set<String> tasks = new HashSet<>(taskDeps.keySet());
			Map<String,Status> taskStatus = taskDeps.keySet().stream().collect(
					Collectors.toMap(t -> t, t -> Status.PENDING));
					
			ExecutorService executor = Executors.newCachedThreadPool();

			// Loop while there are tasks that are not complete yet and we haven't failed
			while(taskStatus.values().stream().noneMatch(s -> s == Status.FAILED) &&
					taskStatus.values().stream().anyMatch(s -> s != Status.COMPLETE)) {
				
				tasks.stream()
					// Start tasks that are PENDING and whose deps are COMPLETE
					.filter(t -> 
						taskStatus.get(t) == Status.PENDING &&
						taskDeps.get(t).stream().allMatch(d -> taskStatus.get(d) == Status.COMPLETE))
					// Fork a new lein task for each task that will be started
					.forEach(task -> {
						log.println("Running Leiningen tasks: " + task);
						taskStatus.put(task, Status.RUNNING);
						executor.submit( () -> {
							boolean success = performTask(build, launcher, listener, task);
							taskStatus.put(task, success ? Status.COMPLETE : Status.FAILED);
						});
					});
				
				// Sleep a short while before trying again
				try {
					Thread.sleep(2500);
				} catch(InterruptedException ie) { /* don't care */ }
			}
			
			boolean success = taskStatus.values().stream().noneMatch(s -> s == Status.FAILED);
			listener.finished(success ? Result.SUCCESS : Result.FAILURE);
			return success;
		} else {
			// If not configured as parallel, retain old behaviour
			return performTask(build,launcher,listener, this.task);
		}
	}

	public boolean performTask(AbstractBuild build, Launcher launcher, BuildListener listener, String task) {

		String output;
		EnvVars env = null;
		FilePath workDir = build.getModuleRoot();
		int exitValue;
		boolean success;

		if (task == null || task.trim().equals("")) {
			listener.fatalError("invalid task: " + task);
			build.setResult(Result.ABORTED);
			return false;
		}

		if (subdirPath != null && subdirPath.length() > 0) {
			workDir = new FilePath(workDir, subdirPath);
		}

		try {
			ArgumentListBuilder leinCommand = getLeinCommand(build, launcher, workDir, task);
			String[] cmdarray = leinCommand.toCommandArray();
			env = build.getEnvironment(listener);

			exitValue = launcher.launch().cmds(cmdarray).envs(env).stdout(listener).pwd(workDir).join();

			return (exitValue == 0);
		} catch (IllegalArgumentException e) {
			e.printStackTrace(listener.fatalError("leiningen failed: " + e.getMessage()));
			build.setResult(Result.FAILURE);
			return false;
		} catch (IOException e) {
			Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("leiningen failed: " + e.getMessage()));
			build.setResult(Result.FAILURE);
			return false;
		} catch (InterruptedException e) {
			e.printStackTrace(listener.fatalError("leiningen failed: " + e.getMessage()));
			build.setResult(Result.ABORTED);
			return false;
		}
	}

	private ArgumentListBuilder getLeinCommand(AbstractBuild build, Launcher launcher, FilePath workDir, String task)
			throws IllegalArgumentException, InterruptedException, IOException {

		DescriptorImpl descriptor = (DescriptorImpl) getDescriptor();
		ArgumentListBuilder args = new ArgumentListBuilder();
		String jarPath = descriptor.getJarPath();

		if (jarPath == null || jarPath.trim().equals("")) {
			throw new IllegalArgumentException("leiningen jar path is empty");
		}

		if (!launcher.isUnix()) {
			args.add("cmd.exe", "/C");
		}

		String javaExePath;

		if (build.getProject().getJDK() != null) {
			javaExePath = new File(build.getProject().getJDK().getBinDir()
					+ "/java").getAbsolutePath();
		} else {
			javaExePath = "java";
		}

		args.add(javaExePath);
		args.add("-client");
		args.add("-XX:+TieredCompilation");
		args.add("-Xbootclasspath/a:" +  descriptor.getJarPath());

		// TODO: handle also spaces within the options, like '-Dvalue="some string"'
		if(jvmOpts != null) { // jvmOpts may be null if config was saved in previous plugin version
			for(String jo : jvmOpts.split(" ")) {
				if(! "".equals(jo)) {
					args.add(jo);
				}
			}
		}
		args.add("-Dfile.encoding=UTF-8");
		args.add("-Dmaven.wagon.http.ssl.easy=false");
		args.add("-Dleiningen.original.pwd=" + workDir);
		args.add("-cp");
		args.add(jarPath);
		args.add("clojure.main");
		args.add("-m");
		args.add("leiningen.core.main");

		Matcher matcher = Pattern.compile("[^\\s\"']+|\"([^\"]*)\"|'([^']*)'").matcher(task);
		while (matcher.find()) {
			if (matcher.group(1) != null)
				args.add(matcher.group(1));
			else if (matcher.group(2) != null)
				args.add(matcher.group(2));
			else
				args.add(matcher.group());
		}
		return args;
	}


	// Overridden for better type safety.
	// If your plugin doesn't really define any property on Descriptor,
	// you don't have to do this.
	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl)super.getDescriptor();
	}


	/**
	 * Descriptor for {@link LeiningenBuilder}. Used as a singleton.
	 * The class is marked as public so that it can be accessed from views.
	 *
	 * <p>
	 * See <tt>src/main/resources/hudson/plugins/hello_world/LeiningenBuilder/*.jelly</tt>
	 * for the actual HTML fragment for the configuration screen.
	 */
	@Extension // This indicates to Jenkins that this is an implementation of an extension point.
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		/**
		 * To persist global configuration information,
		 * simply store it in a field and call save().
		 *
		 * <p>
		 * If you don't want fields to be persisted, use <tt>transient</tt>.
		 */
		private String jarPath;

		/**
		 *
		 * Make sure configuration is read at startup
		 *
		 */
		public DescriptorImpl() {
			load();
		}

		public String getJarPath() {
			return jarPath;
		}

		/**
		 * Performs on-the-fly validation of the form field 'name'.
		 *
		 * @param value
		 *      This parameter receives the value that the user has typed.
		 * @return
		 *      Indicates the outcome of the validation. This is sent to the browser.
		 */
		public FormValidation doCheckTask(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please provide a leiningen task command line");
			return FormValidation.ok();
		}

		public FormValidation doCheckJarPath(@QueryParameter String value)
				throws IOException, ServletException {
			if (value.length() == 0)
				return FormValidation.error("Please provide a valid leiningen JAR path");
			return FormValidation.ok();
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			// Indicates that this builder can be used with all kinds of project types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName() {
			return "Build project using leiningen";
		}

		@Override


		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			// To persist global configuration information,
			// set that to properties and call save().
			jarPath = formData.getString("jarPath");
			save();
			return super.configure(req,formData);
		}
	}
}
