import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildSteps.csharpScript
import jetbrains.buildServer.configs.kotlin.buildSteps.script

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2024.12"

project {

    buildType(RetrieveIssues)
}

object RetrieveIssues : BuildType({
    name = "Retrieve Issues"

    artifactRules = """
        response.txt
        markdown.md
        response_jq.txt
        filtered_response.txt
    """.trimIndent()

    params {
        password("env.GITHUB_TOKEN", "credentialsJSON:da821e9b-22a7-4880-b8bd-995fa9280af8")
        password("env.YT_TOKEN", "credentialsJSON:624bf789-1956-423f-b46e-991d4be44a01")
        text("full-version-escaped", "", display = ParameterDisplay.HIDDEN, allowEmpty = true)
        text("build-number", "", label = "Build Number", description = """Leave "0" if no build number is yet assigned. If specified, will be appended to the short version: full product version will be in the {short-version (build-number)} format""", display = ParameterDisplay.PROMPT, allowEmpty = true)
        text("release-date", "", label = "Release Date", description = """Enter the value in the "d MMMM yyyy" format (for example, 5 April 2025), or leave empty to use today's date. The date is added to the Release Notes and Previous Downloads articles""", display = ParameterDisplay.PROMPT, allowEmpty = true)
        text("short-version", "", label = "Short TeamCity version", description = "Human-readable TC version in the year.major.minor version (for example, 2024.12.3)", display = ParameterDisplay.PROMPT, allowEmpty = true)
        text("full-version", "", description = """Full TC version as seen in the YouTrack. For example, "{2024.12.2 (174504)}"""", display = ParameterDisplay.HIDDEN, allowEmpty = true)
        text("file-name", "", label = "Release Notes article name", description = """Leave this field empty if the file has the default "teamcity-year-major-minor-release-notes.md" name. This name is calculated from the entered "short version" parameter""", allowEmpty = true)
        param("security_issue_count", "0")
        text("env.repo_branch", "2024.12", display = ParameterDisplay.PROMPT, allowEmpty = true)
    }

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        csharpScript {
            name = "Prepare versions"
            id = "csharpScript"
            content = """
                string fv = String.Equals("%build-number%", "0") ? "%short-version%" : "{%short-version% (%build-number%)}";
                Console.WriteLine("##teamcity[setParameter name='full-version' value='" + fv + "']"); // set full-version
                
                string escapedVersion = Uri.EscapeDataString(fv);
                Console.WriteLine("##teamcity[setParameter name='full-version-escaped' value='" + escapedVersion + "']");
                
                string docname = String.Equals("%file-name%", String.Empty) ? "teamcity-" + "%short-version%".Replace('.', '-') + "-release-notes.md" : "%file-name%";
                Console.WriteLine("##teamcity[setParameter name='file-name' value='" + docname + "']");
            """.trimIndent()
            tool = "%teamcity.tool.TeamCity.csi.DEFAULT%"
        }
        script {
            name = "Extract"
            id = "simpleRunner"
            scriptContent = """
                curl -X GET "https://youtrack.jetbrains.com/api/issues?fields=idReadable,summary,customFields(value(name),name)&query=Project:%20TeamCity%20Fix%20versions:%20%full-version-escaped%%20visible%20to:%20%7BAll%20Users%7D%20%23Fixed%20%23Testing%20-%7BTrunk%20issue%7D" \
                  -H "Authorization: Bearer ${'$'}YT_TOKEN" \
                  -H "Accept: application/json" \
                  -o response.txt
                
                # pre-processing the JSON
                # add "| jq -r "[.[] | {summary,idReadable,???}]"
            """.trimIndent()
        }
        script {
            name = "Count security issues"
            id = "Security_issues"
            scriptContent = """
                response=${'$'}(curl -X POST "https://youtrack.jetbrains.com/api/issuesGetter/count?fields=count" \
                     -H "Authorization: Bearer ${'$'}YT_TOKEN" \
                     -H "Content-Type: application/json" \
                     -d '{
                           "query": "project: TeamCity Fix versions: %full-version%  #Testing #Fixed -{Trunk issue} #{Security Problem}"
                         }')
                
                
                count=${'$'}(echo "${'$'}response" | jq '.count')
                
                echo "The number of security issues is: ${'$'}count"
                echo "##teamcity[setParameter name='security_issue_count' value='${'$'}count']"
            """.trimIndent()
        }
        csharpScript {
            name = "Pre-Transform (split into lines by keywords)"
            id = "Transform"
            content = """
                string inputFilePath = "response.txt";
                string outputFilePath = "formatted_response.txt";
                
                if (!File.Exists(inputFilePath)) {
                	Console.WriteLine("Input file not found!");
                	return;
                }
                
                try {
                	string content = File.ReadAllText(inputFilePath);
                
                	string[] keywords = { "\"summary\"", "\"idReadable\"", "\"customFields\"", "\"value\"" };
                
                	string formattedContent = "";
                	int startIndex = 0;
                
                	while (startIndex < content.Length) {
                		int closestIndex = content.Length;
                		string foundKeyword = null;
                
                		// Find the closest occurrence of any keyword
                		foreach (string keyword in keywords) {
                			int index = content.IndexOf(keyword, startIndex);
                			if (index != -1 && index < closestIndex)
                				{
                					closestIndex = index;
                					foundKeyword = keyword;
                			}
                		}
                
                		// If no keyword is found, append the rest of the content and break
                		if (foundKeyword == null) {
                			formattedContent += content.Substring(startIndex);
                		 	break;
                		}
                
                		// Append everything up to the keyword
                		formattedContent += content.Substring(startIndex, closestIndex - startIndex);
                
                		// Add a newline before the keyword (except for the first occurrence)
                		if (formattedContent.Length > 0) {
                			formattedContent += "\n";
                		}
                
                		// Append the keyword
                		formattedContent += foundKeyword;
                
                		// Move startIndex past the keyword
                		startIndex = closestIndex + foundKeyword.Length;
                	}
                
                	File.WriteAllText(outputFilePath, formattedContent);
                	Console.WriteLine("Formatted response written to: " + outputFilePath);
                }
                catch (Exception ex) {
                	Console.WriteLine("Error processing the file: " + ex.Message);
                }
            """.trimIndent()
            tool = "%teamcity.tool.TeamCity.csi.DEFAULT%"
        }
        csharpScript {
            name = "Cleanse"
            id = "Cleanse"
            content = """
                string inputFilePath = "formatted_response.txt";
                string outputFilePath = "filtered_response.txt";
                
                if (!File.Exists(inputFilePath)) {
                	Console.WriteLine("Input file not found!");
                	return;
                }
                
                try {
                	string[] lines = File.ReadAllLines(inputFilePath);
                	string[] substringsToRemove = {
                      "\"summary\":\"",
                      "\",",
                      "\"idReadable\":\"",
                      "\"value\":{\"name\":\"",
                      "\"${'$'}type\":\"EnumBundleElement\"},\"name\":\"Type\"${'$'}type\":\"SingleEnumIssueCustomField\"},{" };
                  
                
                	string[] filteredLines = lines.Where(line =>
                		line.Trim().StartsWith("\"summary\"") ||      
                		line.Trim().StartsWith("\"idReadable\"") ||  
                		line.Contains("\"name\":\"Type\",\"${'$'}type\":\"SingleEnumIssueCustomField\"") 
                	).ToArray();
                  
                  
                	for (int i = 0; i < filteredLines.Count(); i++) {
                		foreach (string substring in substringsToRemove) {
                			filteredLines[i] = filteredLines[i].Replace(substring, string.Empty);
                		}
                	}
                  
                	File.WriteAllLines(outputFilePath, filteredLines);
                	Console.WriteLine("Filtered response written to: " + outputFilePath);
                }
                catch (Exception ex) {
                	Console.WriteLine("Error processing the file: " + ex.Message);
                }
            """.trimIndent()
            tool = "%teamcity.tool.TeamCity.csi.DEFAULT%"
        }
        csharpScript {
            name = "Transform"
            id = "Transform_1"
            content = """
                string inputFilePath = "filtered_response.txt";
                string outputFilePath = "markdown.md";
                
                if (!File.Exists(inputFilePath)) {
                	Console.WriteLine("Input file not found!");
                	return;
                }
                
                try {
                	List<string> lines = File.ReadAllLines(inputFilePath).ToList();
                	List<string> mdLines = new List<string>();
                    
                	int bugs = 0;
                	int tasks = 0;
                	int performance = 0;
                	int features = 0;
                
                  
                	foreach (string line in lines) {
                		features = String.Equals(line, "Feature") ? features+1 : features;
                      bugs = String.Equals(line, "Bug") ? bugs+1 : bugs;
                      tasks = String.Equals(line, "Task") ? tasks+1 : tasks;
                      performance = String.Equals(line, "Performance Problem") ? performance+1 : performance;
                      Console.WriteLine(features + " features; " + bugs + " bugs; " + tasks + " tasks; " + performance + " performance issues.");
                      //line.Split(new string[] { "Bug" }, StringSplitOptions.None).Length - 1;
                		//performance += line.Split(new string[] { "Performance Problem" }, StringSplitOptions.None).Length - 1;
                		//tasks += line.Split(new string[] { "Task" }, StringSplitOptions.None).Length - 1;
                        //features += line.Split(new string[] { "Feature" }, StringSplitOptions.None).Length - 1;
                	}
                	
                	mdLines.Add("[//]: # (title: TeamCity %short-version% Release Notes)");
                	mdLines.Add("[//]: # (auxiliary-id: TeamCity %short-version% Release Notes)");
                	mdLines.Add("");
                	mdLines.Add("");
                    string r_date = String.Equals("%release-date%", String.Empty) ? DateTime.Now.ToString("d MMMM yyyy") : "%release-date%";
                	mdLines.Add("**Build %build-number%, " + r_date + "**");
                  
                  
                  if (features > 0) {
                		mdLines.Add("");
                		mdLines.Add("### Feature");
                		mdLines.Add("");
                		for (int i = 0; i < lines.Count; i++) {
                			if (lines[i].Equals("Feature")) {
                				string issueID = lines[i - 1];
                				string issueSummary = lines[i - 2];
                                mdLines.Add("* [**" + issueID + "**](https://youtrack.jetbrains.com/issue/" + issueID + ") — " + issueSummary);
                			}
                		}
                	}
                  
                  
                	if (tasks > 0) {
                		mdLines.Add("");
                		mdLines.Add("### Task");
                		mdLines.Add("");
                		for (int i = 0; i < lines.Count; i++) {
                			if (lines[i].Equals("Task")) {
                				string issueID = lines[i - 1];
                				string issueSummary = lines[i - 2];
                                mdLines.Add("* [**" + issueID + "**](https://youtrack.jetbrains.com/issue/" + issueID + ") — " + issueSummary);
                			}
                		}
                	}
                  
                	if (bugs > 0) {
                		mdLines.Add("");
                		mdLines.Add("### Bug");
                		mdLines.Add("");
                		for (int i = 0; i < lines.Count; i++) {
                			if (lines[i].Equals("Bug")) {
                				string issueID = lines[i - 1];
                				string issueSummary = lines[i - 2];
                                mdLines.Add("* [**" + issueID + "**](https://youtrack.jetbrains.com/issue/" + issueID + ") — " + issueSummary);
                			}
                		}
                	}
                  
                    if (performance > 0) {
                		mdLines.Add("");
                		mdLines.Add("### Performance Problem");
                		mdLines.Add("");
                		for (int i = 0; i < lines.Count; i++) {
                			if (lines[i].Equals("Performance Problem")) {
                				string issueID = lines[i - 1];
                				string issueSummary = lines[i - 2];
                                mdLines.Add("* [**" + issueID + "**](https://youtrack.jetbrains.com/issue/" + issueID + ") — " + issueSummary);
                			}
                		}
                	}
                  
                	mdLines.Add("");
                	mdLines.Add("### Security");
                	mdLines.Add("");
                  	
                  	string security_count_verb = "%security_issue_count%" switch
                        {
                      		"-1" => "-1 security problems have been fixed.",
                            "1" => "One security problem has been fixed.",
                            "2" => "Two security problems have been fixed.",
                            "3" => "Three security problems have been fixed.",
                            "4" => "Four security problems have been fixed.",
                            "5" => "Five security problems have been fixed.",
                            "6" => "Six security problems have been fixed.",
                            "7" => "Seven security problems have been fixed.",
                      		"8" => "Eight security problems have been fixed.",
                      		"9" => "Nine security problems have been fixed.",
                            _ => "%security_issue_count% security problems have been fixed."
                        };
                  	
                  	
                 	mdLines.Add(security_count_verb + " This number includes both native TeamCity issues and vulnerabilities found in 3rd-party libraries TeamCity depends on. Upstream library issues usually make up the majority of this total number, and are promptly resolved by updating these libraries to their newest versions.");
                	mdLines.Add("");
                  	mdLines.Add("To learn more about fixed vulnerabilities directly related to TeamCity, check out our [Security Bulletin](https://www.jetbrains.com/privacy-security/issues-fixed/?product=TeamCity&version=%short-version%). Security bulletins for new versions are typically published within the next few days after the release date.");
                
                	File.WriteAllText(outputFilePath, string.Join("\n", mdLines));
                	Console.WriteLine("Formatted response written to: " + outputFilePath);
                }
                catch (Exception ex) {
                	Console.WriteLine("Error processing the file: " + ex.Message);
                }
            """.trimIndent()
            tool = "%teamcity.tool.TeamCity.csi.DEFAULT%"
        }
        script {
            name = "GitHub: Post RN"
            id = "simpleRunner_1"
            scriptContent = """
                GH_API="https://api.github.com/repos/JetBrains/teamcity-documentation";
                
                echo "Fetching latest commit SHA from ${'$'}repo_branch..."
                LATEST_COMMIT_SHA=${'$'}(curl -s -H "Authorization: Bearer ${'$'}GITHUB_TOKEN" \
                                         "${'$'}GH_API/branches/${'$'}repo_branch" | jq -r '.commit.sha')
                
                echo "Latest commit SHA: ${'$'}LATEST_COMMIT_SHA"
                
                
                echo "Creating a new branch..."
                CREATE_BRANCH_RESPONSE=${'$'}(curl -s -X POST -H "Authorization: Bearer ${'$'}GITHUB_TOKEN" \
                     -H "Content-Type: application/json" \
                     -d "{\"ref\": \"refs/heads/auto-release-notes\", \"sha\": \"${'$'}LATEST_COMMIT_SHA\"}" \
                     "${'$'}GH_API/git/refs")
                
                if echo "${'$'}CREATE_BRANCH_RESPONSE" | grep -q "ref"; then
                    echo "Branch auto-release-notes created successfully."
                else
                    echo "Error: Failed to create branch."
                    exit 1
                fi
                
                
                # Get the latest SHA of the remote file
                FILE_SHA=${'$'}(curl -s -H "Authorization: Bearer ${'$'}GITHUB_TOKEN" \
                    "${'$'}GH_API/contents/topics/%file-name%?ref=auto-release-notes"| jq -r '.sha')
                    
                echo "File SHA: ${'$'}FILE_SHA"    
                
                
                
                # Encode local file content to Base64
                 ENCODED_CONTENT=${'$'}(base64 -i "markdown.md")
                
                # Replace remote file content
                 curl -s -X PUT -H "Authorization: Bearer ${'$'}GITHUB_TOKEN" \
                    -H "Content-Type: application/json" \
                    -d "{\"message\": \"Commit message\", \"content\": \"${'$'}ENCODED_CONTENT\", \"branch\": \"auto-release-notes\", \"sha\": \"${'$'}FILE_SHA\"}" \
                    "${'$'}GH_API/contents/topics/%file-name%"
            """.trimIndent()
        }
        script {
            name = "GitHub: Merge Request"
            id = "test"
            scriptContent = """
                curl -X POST https://api.github.com/repos/JetBrains/teamcity-documentation/pulls \
                  -H "Authorization: Bearer ${'$'}GITHUB_TOKEN" \
                  -H "Accept: application/vnd.github+json" \
                  -d '{
                    "title": "Autogenerated Release Notes",
                    "head": "auto-release-notes",
                    "base": "%env.repo_branch%",
                    "body": "ABOBA"
                  }'
            """.trimIndent()
        }
    }
})
