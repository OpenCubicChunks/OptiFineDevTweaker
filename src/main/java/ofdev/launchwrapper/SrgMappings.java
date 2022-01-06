package ofdev.launchwrapper;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class SrgMappings {
	//since srg field and method names are guarranted not to collide -  we can store them in one map
	private static final Map<String, String> srgToMcp = new HashMap<>();

	static {
		String location = System.getProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp");
		initMappings(location);
	}

	public static String getNameFromSrg(String srgName) {
		String result = srgToMcp.get(srgName);
		return result == null ? srgName : result;
	}

	private static void initMappings(String property) {
		try (Scanner scanner = new Scanner(new File(property))) {
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				parseLine(line);
			}
		} catch (FileNotFoundException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void parseLine(String line) {
		if (line.startsWith("FD: ")) {
			parseField(line.substring("FD: ".length()));
		}
		if (line.startsWith("MD: ")) {
			parseMethod(line.substring("MD: ".length()));
		}
	}

	private static void parseMethod(String substring) {
		String[] s = substring.split(" ");
		final int SRG_NAME = 0/*, SRG_DESC = 1*/, MCP_NAME = 2/*, MCP_DESC = 3*/;
		int lastIndex = s[SRG_NAME].lastIndexOf('/') + 1;
		s[SRG_NAME] = s[SRG_NAME].substring(lastIndex);
		lastIndex = s[MCP_NAME].lastIndexOf("/") + 1;
		s[MCP_NAME] = s[MCP_NAME].substring(lastIndex);
		srgToMcp.put(s[SRG_NAME], s[MCP_NAME]);
	}

	private static void parseField(String str) {
		if (!str.contains(" ")) {
			return;
		}
		String[] s = str.split(" ");
		assert s.length == 2;
		int lastIndex = s[0].lastIndexOf('/') + 1;
		s[0] = s[0].substring(lastIndex);
		lastIndex = s[1].lastIndexOf("/") + 1;
		s[1] = s[1].substring(lastIndex);
		srgToMcp.put(s[0], s[1]);
	}
}

