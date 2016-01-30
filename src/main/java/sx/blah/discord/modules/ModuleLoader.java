package sx.blah.discord.modules;

import sx.blah.discord.Discord4J;
import sx.blah.discord.api.IDiscordClient;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;

/**
 * This class is used to manage loading and unloading modules for a discord client.
 */
public class ModuleLoader {
	
	/**
	 * This is the directory external modules are located in
	 */
	public static final String MODULE_DIR = "modules";
	protected static final List<Class<? extends IModule>> modules = new ArrayList<>();
	
	private IDiscordClient client;
	private List<IModule> loadedModules = new ArrayList<>();
	
	static {
		File modulesDir = new File(MODULE_DIR);
		if (modulesDir.exists())
			assert modulesDir.isDirectory();
		else
			assert modulesDir.mkdir();
		
		if (modulesDir.listFiles().length > 0) {
			Discord4J.LOGGER.info("Loading {} external module(s)...", modulesDir.listFiles().length);
			for (File file : modulesDir.listFiles())
				loadModules(file);
		}
	}
	
	public ModuleLoader(IDiscordClient client) {
		this.client = client;
		
		for (Class<? extends IModule> clazz : modules) {
			try {
				IModule module = clazz.newInstance();
				Discord4J.LOGGER.info("Loading module {} v{} by {}", module.getName(), module.getVersion(), module.getAuthor());
				if (canModuleLoad(module)) {
					loadedModules.add(module);
				} else {
					Discord4J.LOGGER.warn("Skipped loading of module {} (expected Discord4J v{} instead of v{})", module.getName(), module.getMinimumDiscord4JVersion(), Discord4J.VERSION);
				}
			} catch (InstantiationException | IllegalAccessException e) {
				Discord4J.LOGGER.error("Unable to load module "+clazz.getName()+"!", e);
			}
		}
		
		if (Configuration.AUTOMATICALLY_ENABLE_MODULES) {//Handles module load order and loads the modules
			List<IModule> toLoad = new ArrayList<>(loadedModules);
			List<IModule> loaded = new ArrayList<>();
			while (toLoad.size() > 0) {
				for (IModule module : toLoad) {
					Class<? extends IModule> clazz = module.getClass();
					if (clazz.isAnnotationPresent(Requires.class)) {
						Requires annotation = clazz.getAnnotation(Requires.class);
						if (!hasDependency(loaded, annotation.value())) {
							if (!hasDependency(toLoad, annotation.value())) {
								Discord4J.LOGGER.warn("Skipped and unloaded module {}. It's missing required module class {}", module.getName(), annotation.value());
								toLoad.remove(module);
								loadedModules.remove(module);
							}
							continue;
						}
					}
					module.enable(client);
					loaded.add(module);
					toLoad.remove(module);
				}
			}
		}
	}
	
	/**
	 * Gets the modules loaded in this ModuleLoader instance.
	 * 
	 * @return The list of loaded modules.
	 */
	public List<IModule> getLoadedModules() {
		return loadedModules;
	}
	
	/**
	 * Manually loads a module. NOTE: This doesn't enable the module!
	 * 
	 * @param module The module to load.
	 */
	public void loadModule(IModule module) {
		loadedModules.add(module);
	}
	
	private boolean hasDependency(List<IModule> modules, String className) {
		for (IModule module : modules)
			if (module.getClass().getName().equals(className))
				return true;
		return false;
	}
	
	private boolean canModuleLoad(IModule module) {
		String[] versions = module.getVersion().toLowerCase().replace("-SNAPSHOT", "").split("\\.");
		String[] discord4jVersion = Discord4J.VERSION.toLowerCase().replace("-SNAPSHOT", "").split("\\.");
		for (int i = 0; i < Math.min(versions.length, 3); i++) {
			if (!(Integer.parseInt(versions[i]) <= Integer.parseInt(discord4jVersion[i])))
				return false;
		}
		return true;
	}
	
	/**
	 * Loads a jar file and automatically adds any modules.
	 * 
	 * @param file The jar file to load.
	 */
	public static synchronized void loadModules(File file) { //A bit hacky, but oracle be dumb and encapsulates URLClassLoader#addUrl()
		try {
			//Executes would should be URLCLassLoader.addUrl(file.toURI().toURL());
			URLClassLoader loader = (URLClassLoader)ClassLoader.getSystemClassLoader();
			URL url = file.toURI().toURL();
			for (URL it : Arrays.asList(loader.getURLs())) {//Ensures duplicate libraries aren't loaded 
				if (it.equals(url)){
					return;
				}
			}
			Method method = URLClassLoader.class.getDeclaredMethod("addURL", new Class[]{URL.class});
			method.setAccessible(true);
			method.invoke(loader, new Object[]{url});
			
			//Scans the jar file for classes which have IModule as a super class
			List<String> classes = new ArrayList<>();
			JarFile jar = new JarFile(file);
			jar.stream().forEach(jarEntry -> {
				if (!jarEntry.isDirectory() && jarEntry.getName().endsWith(".class")) {
					String className = jarEntry.getName().replace('/', '.');
					classes.add(className.substring(0, className.length() - ".class".length()));
				}
			});
			for (String clazz : classes) {
				Class classInstance = Class.forName(clazz);
				if (IModule.class.isAssignableFrom(classInstance)) {
					addModuleClass(classInstance);
				}
			}
		} catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | IOException | ClassNotFoundException e){
			Discord4J.LOGGER.error("Unable to load module "+file.getName()+"!", e);
		}
	}
	
	/**
	 * Manually adds a module class to be considered for loading.
	 * 
	 * @param clazz The module class.
	 */
	public static void addModuleClass(Class<? extends IModule> clazz) {
		modules.add(clazz);
	}
}
