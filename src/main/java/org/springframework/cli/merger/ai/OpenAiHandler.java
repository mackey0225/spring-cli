/*
 * Copyright 2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cli.merger.ai;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cli.SpringCliException;
import org.springframework.cli.merger.ai.service.DescriptionRewriteAiService;
import org.springframework.cli.merger.ai.service.GenerateCodeAiService;
import org.springframework.cli.merger.ai.service.ProjectNameHeuristicAiService;
import org.springframework.cli.util.IoUtils;
import org.springframework.cli.util.RootPackageFinder;
import org.springframework.cli.util.TerminalMessage;
import org.springframework.util.StreamUtils;

public class OpenAiHandler {

	private static final Logger logger = LoggerFactory.getLogger(OpenAiHandler.class);

	private final GenerateCodeAiService generateCodeAiService;

	public OpenAiHandler(GenerateCodeAiService generateCodeAiService) {
		this.generateCodeAiService = generateCodeAiService;
	}

	public void add(String description, String path, boolean preview, boolean rewrite,
			TerminalMessage terminalMessage) {
		Path projectPath = getProjectPath(path);

		ProjectNameHeuristicAiService projectNameHeuristic = new ProjectNameHeuristicAiService(terminalMessage);
		logger.debug("Deriving main Spring project required...");
		ProjectName projectName = projectNameHeuristic.deriveProjectName(description);
		logger.debug("Done.  The code will primarily use " + projectName.getSpringProjectName());

		if (rewrite) {
			DescriptionRewriteAiService descriptionRewriteAiService = new DescriptionRewriteAiService(terminalMessage);
			description = descriptionRewriteAiService.rewrite(description);
		}
		terminalMessage.print("");
		terminalMessage.print("The description has been rewritten to be: " + description);
		terminalMessage.print("");
		Map<String, String> context = createContext(description, projectName, projectPath);

		String readmeResponse = this.generateCodeAiService.generate(context);

		writeReadMe(projectName, readmeResponse, projectPath, terminalMessage);
		if (!preview) {
			List<ProjectArtifact> projectArtifacts = createProjectArtifacts(readmeResponse);
			ProjectArtifactProcessor projectArtifactProcessor = new ProjectArtifactProcessor(projectArtifacts,
					projectPath, terminalMessage);
			ProcessArtifactResult processArtifactResult = projectArtifactProcessor.process();
			// TODO mention artifacts not processed
		}
	}

	public void apply(String file, String path, TerminalMessage terminalMessage) {
		Path projectPath = getProjectPath(path);
		Path readmePath = projectPath.resolve(file);
		if (Files.notExists(readmePath)) {
			throw new SpringCliException("Could not find file " + file);
		}
		if (!Files.isRegularFile(readmePath)) {
			throw new SpringCliException("The Path " + readmePath + " is not a regular file, can't read");
		}
		try (InputStream stream = Files.newInputStream(readmePath)) {
			String response = StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
			List<ProjectArtifact> projectArtifacts = createProjectArtifacts(response);
			ProjectArtifactProcessor projectArtifactProcessor = new ProjectArtifactProcessor(projectArtifacts,
					projectPath, terminalMessage);
			projectArtifactProcessor.process();
		}
		catch (IOException ex) {
			throw new SpringCliException("Could not read file " + readmePath.toAbsolutePath(), ex);
		}

	}

	private void writeReadMe(ProjectName projectName, String text, Path projectPath, TerminalMessage terminalMessage) {
		Path output = projectPath.resolve("README-ai-" + projectName.getShortPackageName() + ".md");
		if (output.toFile().exists()) {
			try {
				Files.delete(output);
			}
			catch (IOException ex) {
				throw new SpringCliException("Could not delete readme file: " + output.toAbsolutePath(), ex);
			}
		}
		try (Writer writer = new BufferedWriter(new FileWriter(output.toFile()))) {
			writer.write(text);
		}
		catch (IOException ex) {
			throw new SpringCliException("Could not write readme file: " + output.toAbsolutePath(), ex);
		}
		terminalMessage.print("Read the file " + "README-ai-" + projectName.getShortPackageName()
				+ ".md for a description of the code.");
	}

	private static Path getProjectPath(String path) {
		Path projectPath;
		if (path == null) {
			projectPath = IoUtils.getWorkingDirectory();
		}
		else {
			projectPath = IoUtils.getProjectPath(path);
		}
		return projectPath;
	}

	private Map<String, String> createContext(String description, ProjectName projectName, Path path) {
		Map<String, String> context = new HashMap<>();
		context.put("build-tool", "maven");
		context.put("package-name", calculatePackage(projectName.getShortPackageName(), path));
		context.put("spring-project-name", projectName.getSpringProjectName());
		context.put("description", description);
		return context;
	}

	private String calculatePackage(String shortPackageName, Path path) {
		Optional<String> rootPackage = RootPackageFinder.findRootPackage(path.toFile());
		if (rootPackage.isEmpty()) {
			throw new SpringCliException("Could not find root package from path " + path.toAbsolutePath());
		}
		return rootPackage.get() + ".ai." + shortPackageName;
	}

	List<ProjectArtifact> createProjectArtifacts(String response) {
		ProjectArtifactCreator projectArtifactCreator = new ProjectArtifactCreator();
		List<ProjectArtifact> projectArtifacts = projectArtifactCreator.create(response);
		return projectArtifacts;
	}

}
