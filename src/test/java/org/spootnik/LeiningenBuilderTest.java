package org.spootnik;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class LeiningenBuilderTest {

	@Test
	public void testTaskDeps() {
		
		Map<String,List<String>> t = new LeiningenBuilder(null, null, null, false).parseLeinTasks(
				"clean\n"+
				"deps: clean\n"+
				"compile: deps\n"+
				"cljsbuild once prod: deps\n"+
				"test2junit: compile\n"+
				"less once: compile\n"+
				"uberjar: compile; cljsbuild once prod");
		
		// less once has 1 dependency: compile
		assertEquals(1, t.get("less once").size());
		assertEquals("compile", t.get("less once").get(0));
		
		// uberjar has 2 deps: compile and cljsbuild once prod
		List<String> uberjar = t.get("uberjar");
		assertEquals(2, uberjar.size());
		assertEquals("compile", uberjar.get(0));
		assertEquals("cljsbuild once prod", uberjar.get(1));
	}

}
