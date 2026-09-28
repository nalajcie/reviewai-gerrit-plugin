# Configuration

You have the option to establish global settings, or independently configure specific projects. If you choose
independent configuration, the corresponding project settings will override the global parameters.

## Global Configuration

To configure these parameters, you need to modify your Gerrit configuration file (`gerrit.config`). The file format is
as follows:

```
[plugin "reviewai-gerrit-plugin"]
    # Required parameters
    gerritUserName = {gerritUserName}
    aiTokens = OpenAI/{openAiToken}
    ...

    # Optional parameters
    aiProviders = OpenAI
    aiProviders = MoonShot
    aiModels = OpenAI/gpt-5.2
    aiModels = MoonShot/moonshot-v1-8k
    aiModelsDefault = OpenAI/gpt-5.2
    aiPricing = OpenAI/custom-model,input=1.00,cachedInput=0.10,output=5.00
    aiAdministratorsGroup = Administrators
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

### Secure Configuration

It is highly recommended to store sensitive information such as `aiTokens` in the `secure.config` file. Please edit
`$gerrit_site/etc/secure.config` and include the following details:

```
[plugin "reviewai-gerrit-plugin"]
    aiTokens = OpenAI/{openAiToken}
    aiTokens = MoonShot/{moonShotToken}
```

If you wish to encrypt the information within the `secure.config` file, you can refer
to: https://gerrit.googlesource.com/plugins/secure-config

## Project Configuration

To add the following content, please edit the `project.config` file in `refs/meta/config`:

```
[plugin "reviewai-gerrit-plugin"]
    # Optional parameters
    aiProviders = {providerRoute}
    aiModels = {providerModelRoute}
    aiPricing = {providerModelPricing}
    aiSystemPromptInstructions = {aiSystemPromptInstructions}
    ...
```

### Secure Configuration

Please ensure **strict control over the access permissions of `refs/meta/config`** if sensitive information such as
`aiTokens` is configured in the `project.config` file within `refs/meta/config`.

## Shared PostgreSQL Storage

By default, ReviewAI stores its persisted state in an H2 database under the plugin data directory. Deployments with
multiple Gerrit primaries can instead configure one shared PostgreSQL database so that each node sees the same plugin
state.

Database settings are global only. Add the connection URL and, when required by the database, the username to
`$gerrit_site/etc/gerrit.config` on every Gerrit node:

```ini
[plugin "reviewai-gerrit-plugin"]
    storeUrl = jdbc:postgresql://db-host:5432/reviewai
    storeUsername = reviewai
```

Store the password in `$gerrit_site/etc/secure.config` rather than `gerrit.config`:

```ini
[plugin "reviewai-gerrit-plugin"]
    storePassword = {databasePassword}
```

A `storeUrl` beginning with `jdbc:postgresql:` selects the PostgreSQL dialect. If `storeUrl` is absent or blank, the
plugin continues to use its local H2 database. Project-level configuration cannot override these settings.

The PostgreSQL JDBC driver is not bundled with the plugin. Download a compatible
[pgJDBC driver](https://jdbc.postgresql.org/download/) JAR and install it in Gerrit's `lib` directory on every Gerrit
node before starting the plugin. For example:

```bash
GERRIT_SITE_PATH=/path/to/gerrit
PGJDBC_JAR=/path/to/postgresql-{version}.jar

install -m 0644 "$PGJDBC_JAR" "$GERRIT_SITE_PATH/lib/"
```

Adding a JAR to `$gerrit_site/lib` requires a full Gerrit restart; reloading only the plugin does not rebuild Gerrit's
runtime classpath. For an installation managed by `gerrit.sh`, restart it with:

```bash
"$GERRIT_SITE_PATH/bin/gerrit.sh" restart
```

The database, user, and privileges must also be provisioned in advance; ReviewAI creates and updates only its own
tables. All Gerrit primaries must use the same connection settings, have the JDBC driver installed, and be able to
reach the database.

Switching `storeUrl` does not copy existing data from H2. It initializes the ReviewAI schema in PostgreSQL and leaves
the local H2 data unchanged. If existing conversations and other persisted plugin state must be retained, migrate the
data during a maintenance window before enabling PostgreSQL on all nodes.

## AI Provider Routes

The plugin supports multiple AI providers through LangChain. The Review Agent exposes each configured provider/model
combination using `/` syntax.

Supported providers are:

- OpenAI
- DeepSeek
- MoonShot
- Gemini
- Ollama

Model and token settings are grouped by the provider part of the route. If a provider is configured without explicit
models, the plugin exposes the built-in defaults for that provider.

```
[plugin "reviewai-gerrit-plugin"]
    aiProviders = OpenAI
    aiProviders = DeepSeek
    aiProviders = MoonShot
    aiProviders = Ollama

    aiModels = OpenAI/gpt-5.4
    aiModels = OpenAI/gpt-4.1
    aiModels = DeepSeek/deepseek-v4-flash
    aiModels = MoonShot/moonshot-v1-8k
    aiModels = llama3.2
    aiModelsDefault = OpenAI/gpt-5.4

    aiTokens = OpenAI/{openAiToken}
    aiTokens = DeepSeek/{deepSeekToken}
    aiTokens = MoonShot/{moonShotToken}
```

With this configuration, the Review Agent exposes `OpenAI/gpt-5.4`, `OpenAI/gpt-4.1`,
`DeepSeek/deepseek-v4-flash`, `MoonShot/moonshot-v1-8k`, and `Ollama/llama3.2`.
Ollama does not require an `aiTokens` entry. A bare model can also be configured as `aiModels = llama3.2`. When no
configured token-backed provider identifies the bare model route, the plugin guesses `Ollama/llama3.2`. If a bare model
matches a configured or default model for a token-backed provider that has a token, that provider route is used.

## AI Pricing Overrides

`aiPricing` overrides a built-in price or adds pricing for a custom model route used by estimated-cost telemetry. It is
a repeatable setting with one entry per exact provider/model route. All prices are in USD per one million tokens:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiPricing = <Provider>/<model>,input=<price>,output=<price>[,cachedInput=<price>][,cacheWrite=<price>][,longThreshold=<tokens>,longInput=<price>,longCachedInput=<price>,longCacheWrite=<price>,longOutput=<price>]
```

Chiama| Field | Required | Meaning and default |
| --- | --- | --- |
| `input` | Yes | Regular input-token price. |
| `output` | Yes | Output-token price. |
| `cachedInput` | No | Cached-input price; defaults to `input`. |
| `cacheWrite` | No | Cache-write price; defaults to `input`. |
| `longThreshold` | No | Positive input-token boundary above which long-context prices apply. |
| `longInput` | No | Long-context input price; defaults to `input`. |
| `longCachedInput` | No | Long-context cached-input price; defaults to `cachedInput`. |
| `longCacheWrite` | No | Long-context cache-write price; defaults to `longInput`. |
| `longOutput` | No | Long-context output price; defaults to `output`. |

For example, the following entry adds a custom route with cache and long-context pricing:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiModels = OpenAI/custom-model
    aiPricing = OpenAI/custom-model,input=1.00,cachedInput=0.10,cacheWrite=1.25,output=5.00,longThreshold=200000,longInput=2.00,longCachedInput=0.20,longCacheWrite=2.50,longOutput=7.50
```

The route is matched exactly. For example, pricing for `OpenAI/gpt-5.4` is not automatically applied to
`OpenAI/gpt-5.4-2026-06-15`; the snapshot needs its own entry. Prices must be zero or positive. An invalid entry is
ignored and logged, leaving any valid built-in price unchanged.

Entries from `gerrit.config` and a project's `project.config` are combined. If both scopes define the same route, the
project entry is applied last and takes precedence. Ollama and mock routes are excluded from cost tracking. See
[Telemetry](telemetry.md#cost-calculation) for the built-in catalog, calculation rules, and exported cost
metrics.

## AI Budgets

Optional budgets cap the estimated AI cost. They use the same estimate as the cost telemetry, so they only count
provider/model routes with known pricing (built-in or `aiPricing`); Ollama and mock routes cost nothing. All values are
in USD. A budget that is unset or `0` has no limit, which is the default.

```ini
[plugin "reviewai-gerrit-plugin"]
    aiBudgetDailyUsd = 20
    aiBudgetMonthlyUsd = 300
    aiBudgetProjectMonthlyUsd = 50
```

- `aiBudgetDailyUsd`: Global-only budget for the whole site per UTC day. Read only from `gerrit.config`.
- `aiBudgetMonthlyUsd`: Global-only budget for the whole site per UTC calendar month. Read only from `gerrit.config`.
- `aiBudgetProjectMonthlyUsd`: Budget per project and UTC calendar month. It is read with project inheritance, so it
  can be set once on a parent project (for example, `All-Projects` or a shared parent) and overridden by a child
  project. A value in `gerrit.config` is the default for projects that do not set one.

The estimated cost of every AI response is stored in the ReviewAI database (H2 or the shared PostgreSQL storage),
aggregated by UTC day and project, so the spend survives plugin reloads and Gerrit restarts.

When a budget is exhausted:

- Automatic reviews (new patch sets and deferred reviews) are skipped from 100 % of the budget. The plugin logs one
  `INFO` line naming the budget, its configured value and the estimated spend.
- Manual requests (`/review` and other commands addressed to the AI, and Review Agent chat) are still allowed up to
  120 % of the budget. Above that, the AI is not called and a short warning is posted to the change instead.

Budgets are checked before a request starts, so a single large request can take the spend above the budget. The
estimate is not an invoice; see [Telemetry](telemetry.md#cost-calculation). Administrators can read the current spend
from the [usage endpoint](telemetry.md#ai-usage-endpoint).

## Conditional AI Review Trigger

`aiReviewApplicableIf` delays automatic AI review until a Gerrit submit-requirement expression matches the change.
The expression uses Gerrit's submit-requirement query syntax, so Gerrit evaluates label votes and other change
conditions instead of the plugin implementing its own comparison rules.

If this option is unset or empty, Patch Sets are reviewed immediately as before. The option can be set globally or in
a project's configuration. For example, to wait until CI has voted `Verified+1` or higher:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified>=1
```

Multiple conditions can be combined in one expression. The following waits for both CI verification and a positive
Code-Review vote:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified>=1 AND label:Code-Review>=1
```

Gerrit label expressions can also check maximum and minimum votes, voter identity, and vote counts. For example, this
condition requires the maximum `Verified` vote and rejects a minimum-vote veto:

```ini
[plugin "reviewai-gerrit-plugin"]
    aiReviewApplicableIf = label:Verified=MAX AND -label:Verified=MIN
```

Branch, file, footer, author, and other predicates supported by Gerrit submit requirements can be combined with label
conditions. `is:submittable` cannot be used because Gerrit disallows recursive submit-requirement evaluation. Refer to
the [Gerrit submit-requirement expression documentation](https://gerrit-documentation.storage.googleapis.com/Documentation/3.13.1/config-submit-requirements.html#query_expression_syntax)
for the available operators and escaping rules.

When a Patch Set is created, the plugin evaluates the expression before starting its review. If it does not match, the
Patch Set is skipped. A later label vote causes the expression to be evaluated again; when it becomes applicable, the
plugin starts the deferred review automatically. Each change in a topic review is evaluated independently. Invalid
expressions and evaluation failures are logged and fail closed, so they do not start an AI review.

### Condition Labels and CI Awareness

When `aiReviewApplicableIf` references labels, ReviewAI supplies the current value and configured description of each
Condition Label to the review workflow. Agents treat a label as evidence only when its description is directly
relevant to the concern being assessed. A positive vote or a label name alone is not blanket proof that an unrelated
concern has been resolved. For example, `Verified+1` supports marking a compilation concern as fixed only when the
`Verified` label description conclusively establishes that the relevant code was compiled successfully.

During a follow-up review, conclusive label evidence can mark a tracked concern as fixed. At specialization level 2,
the feedback classifier can also exclude a specialized agent for the current review, but only when a positive label's
description covers that agent's complete scope. See
[User Feedback Classification](architecture/review-agents.md#user-feedback-classification) for the classification,
agent-selection, and persistence rules.

Currently, deferred reevaluation is driven by label-bearing `comment-added` events. Conditions that become true only
after another event, such as deleting a veto vote or changing WIP, topic, or hashtag state, are applied on the next
Patch Set or label-vote event rather than immediately.

## Multi-Project Review Groups

With `change.submitWholeTopic = true`, Gerrit submits a topic that spans several repositories as one unit, for example
a superproject change that moves gitlinks together with the submodule changes those gitlinks point to. By default each
repository is reviewed on its own, so the AI never sees the other half of such a change. Set
`topicReviewScope = SUBMITTED_TOGETHER` to review the whole group instead:

```ini
[plugin "reviewai-gerrit-plugin"]
    topicReviewScope = SUBMITTED_TOGETHER
    topicPatchSetWaitMs = 10000
```

- The group is the set of open changes that Gerrit's `submitted_together` returns for the triggering change, in any
  project. A group of one change is reviewed as a normal change.
- Automatic and deferred reviews wait `topicPatchSetWaitMs` for the other events of the group, then review it once per
  set of change and Patch Set numbers. A group contained in a larger group of the same batch is dropped. A manual
  review of a topic skips the wait and the deduplication.
- With `aiReviewApplicableIf`, the group is reviewed only when every reviewable member matches the expression of its
  own project. A vote on any member re-evaluates the group.
- Members whose project has no AI review permission, denies it on the branch, or sets `aiReviewPatchSet = false` are
  sent as read-only context and receive no comments or votes.
- Paths in the merged patch are prefixed with `reviewai-topic-change-<N>/<project>/`, and the patch starts with a list
  of the members (prefix, change number, project, branch and subject). Replies are published on the member they refer
  to. The ON_DEMAND tools resolve prefixed paths to that member's repository at its current Patch Set.
- Gitlink updates are kept and rendered as `submodule <path>: <old> -> <new>`, followed by
  `(= change <N> in <project>)` when the new commit is the current revision of another member.
- When the merged patch exceeds `maxReviewLines`, only the triggering change is reviewed, with the member list
  prepended, and the fallback is logged.

## Optional Parameters

- `aiProviders`: Selects provider routes to expose. The default value is `OpenAI`.
- `aiModels`: Selects model routes by provider. When no models are configured for an exposed provider, the plugin
  exposes built-in defaults.
- `aiModelsDefault`: Selects the default model by provider/model route, such as `OpenAI/gpt-5.4`. This model is used
  for automatic Patch Set reviews and as the initial Review Agent dropdown value when no model has been selected yet.
  If unset or not found in the expanded `aiModels` list, the first available provider/model route is used.
- `aiPricing`: Repeatable exact provider/model pricing override used by estimated-cost telemetry. See
  [AI Pricing Overrides](#ai-pricing-overrides).
- `aiBudgetDailyUsd`, `aiBudgetMonthlyUsd`, `aiBudgetProjectMonthlyUsd`: Optional estimated-cost budgets in USD. See
  [AI Budgets](#ai-budgets).
- `aiTokens`: Provides provider tokens. Configure these as `OpenAI/{token}`, `DeepSeek/{token}`,
  `MoonShot/{token}`, and so on. Ollama does not require a token.
- `aiDomain`: Defines the base endpoint for the selected provider. By default, it uses the provider’s standard domain:
  `https://api.openai.com` (OpenAI), `https://generativelanguage.googleapis.com` (Gemini),
  `https://api.deepseek.com` (DeepSeek), `https://api.moonshot.ai` (Moonshot), or `http://localhost:11434` (Ollama).
  Override only when you need a custom endpoint; leaving it unset lets the plugin pick the provider default
  automatically.
- `mockAiAddress`: Configures a custom address for a mock AI server. When set, a `mock-ai` model is added for each
  configured provider route, such as `OpenAI/mock-ai`, `MoonShot/mock-ai`, or `Ollama/mock-ai`. Selecting one of these
  models keeps the same provider and token behavior as the corresponding live model route, but sends AI requests to the
  configured mock server address instead. Because mock models are appended to the regular model list, they can be
  selected through `aiModelsDefault` like any other model.
- `aiSystemPromptInstructions`: You can customize the default instructions ("Act as a PatchSet Reviewer") to your
  preferred prompt.
- `aiReviewTemperature`: Specifies the temperature setting for AI when reviewing a Patch Set, with a default
  setting of 0.2. Higher values like 0.8 will make the output more random, while lower values like 0.2 will make it more
  focused and deterministic. Some model families do not support temperature; for those models, the plugin omits the
  temperature parameter.
- `aiCommentTemperature`: Specifies the temperature setting for AI when replying to a comment, with a default setting of
  1.0.
- `aiReviewPatchSet`: Set to true by default. When switched to false, it disables the automatic review of Patch Sets as
  they are created or updated.
- `aiReviewCommitMessages`: The default value is true. When enabled, this option also verifies if the commit message
  matches with the content of the Change Set.
- `aiReviewApplicableIf`: Gerrit submit-requirement expression that must match before an automatic AI review starts.
  See [Conditional AI Review Trigger](#conditional-ai-review-trigger).
- `storeUrl`: Global-only external JDBC URL. A `jdbc:postgresql:` URL enables shared PostgreSQL storage; when unset,
  ReviewAI uses its local H2 database. See [Shared PostgreSQL Storage](#shared-postgresql-storage).
- `storeUsername`: Global-only username for the external database, if its authentication configuration requires one.
- `storePassword`: Global-only password for the external database, if required. Store it in `secure.config`.
- `aiAdministratorsGroup`: Gerrit group whose members can use administrator-only ReviewAI commands and view
  administrator-only details with the Development build. If this option is not set, or the configured group does not
  exist in Gerrit, the plugin falls back to the Gerrit Administrators group.
- `directive`: Directives are mandatory instructions written in plain English that AI must adhere to during its reviews.
  You can provide a single directive or multiple directives.

  Example of multiple directive configuration:

```
directive = Be constructive, respectful and concise
directive = End each reply with \"Hope this helps!\"
```

**NOTE**: Double quotes need to be escaped in directives content.

- `commitMessageDirective`: Mandatory rules, like `directive`, that apply only to the review of the commit message.
  They are added to the commit message review requirement of the unified `SINGLE_AGENT` prompt, the `SCOPED_AGENTS`
  commit-message agent and the `SPECIALIZED_AGENTS` `COMMIT_MESSAGE` agent, and are left out of every Patch Set code
  prompt, so rules such as subject length or required trailers are not reported against code. They have no effect when
  `aiReviewCommitMessages` is false. As with `directive`, the values from `gerrit.config` and from the project
  configuration are combined; within the project hierarchy, the nearest project that sets the key replaces the values
  of its parents.

```
commitMessageDirective = "Subject: 'module: summary', at most 72 characters, no trailing period."
commitMessageDirective = "A ticket trailer (for example Bug: 123) is required."
```

- `enabledFileExtensions`: This limits the reviewed files to the given types. Default file extensions are "py, java, js,
  ts, html, css, cs, cpp, c, h, php, rb, swift, kt, r, jl, go, scala, pl, pm, rs, dart, lua, sh, vb, bat".

  **NOTE**: Extensions without a leading dot (e.g., 'py') are also accepted. Exact file names (e.g., 'Jenkinsfile',
  'Makefile', 'CMakeLists.txt') can also be listed to include extensionless or specific files. Dotfiles (e.g.,
  '.gitignore', '.editorconfig') are supported as well. Use `ALL` to enable every file.
- `disabledFileExtensions`: This excludes the given file types from review. It accepts the same extension, exact file
  name, and dotfile formats as `enabledFileExtensions`. Disabled entries take precedence over enabled entries. The
  default is empty. Use `ALL` to disable every file.
- `enabledVoting`: Initially disabled (false). If set to true, allows AI to cast a vote on each reviewed Patch Set by
  assigning a score.
- `convertNeutralReviewScoreToPositive`: Enabled by default (true). When enabled, a neutral final review score (`0`)
  is submitted as `+1` when the permitted voting range allows it. Set it to false to keep neutral reviews at `0`.
- `filterCommentsRelevanceThreshold`: Any review comment assigned a relevance score by AI below this threshold will not
  be shown. The default threshold is set at 0.6.
- `aiRelevanceRules`: This option allows customization of the rules AI uses to determine the relevance of a task.
- `aiProjectInstructionsInReviews`: Disabled by default (false). The file `.gerrit/ai-instructions.md` at the tip of
  the target branch is always added to the prompts that answer comments and requests. When this option is enabled, it
  is also added, as a separate "Project Instructions" section, to every prompt that reviews a Patch Set: the unified
  review, the scoped and specialized agents, concern review and the new-issue finder. Suggest prompts are unchanged.
  Because the file is read from the target branch, a change that edits it is reviewed with the previous version.
- `selectiveLogLevelOverride`: This setting allows for overriding the log level of specific messages, ensuring they are
  logged even if their level is above the current setting. This is useful for debugging without the need to set the
  overall log level to DEBUG, which could result in excessive DEBUG messages from sources like gerrit and other plugins.
  Some usage examples can be found at [Selective Production Logging](development.md#selective-production-logging) section.
- `aiFullFileReview`: Enabled by default. Activating this option sends both unchanged lines and changes to AI for
  review, offering additional context information. Deactivating it (set to false) results in only the changed lines
  being submitted for review.
- `maxReviewLines`: The default value is 1000. This sets a limit on the number of lines of code included in the review.
- `patchContextLines`: The default value is 3. This sets how many unchanged context lines are included around each
  changed hunk in the patch passed to AI. Set it to 0 to include only changed lines.
- `codeContextPolicy`: Defines the code context policy used when AI needs repository context outside the formatted
  patch. The default value is `NONE`.
  The currently supported policies are:
    - **ON_DEMAND**: Lets the model request repository context during review through tool calls for listing the file
      tree, searching references, and reading file content.
    - **NONE**: Does not expose repository context tools. Reviews and interactions rely on the formatted patch and
      Gerrit discussion history only.
- `codeContextProject`: Repeatable. Additional repositories that the ON_DEMAND tools can read, as
  `<project>[:<ref>]`. The ref defaults to `refs/heads/master`; a bare branch name is expanded to `refs/heads/<branch>`.
  Each project is resolved once per review to the commit its ref points to and exposed read-only under the path prefix
  `reviewai-context/<project>/`: `tree` lists it (at most 2000 entries), `get_content` reads text files up to 512 KiB,
  and `grep` searches it when its `path` argument points into it (at most 5000 files and 200 matches). Binary files,
  Git LFS pointers, submodules and larger files are skipped. A project is exposed only when the Gerrit AI user can read
  the ref; other entries are skipped with a warning in the log. The files are marked as not part of the change, and the
  AI is told never to comment on them. Use it for shared headers, coding standards or specifications that reviews
  should be able to consult. Values from `gerrit.config` and from the project configuration are combined; within the
  project hierarchy, the nearest project that sets the key replaces the values of its parents. Has no effect with
  `codeContextPolicy = NONE`.

```
codeContextProject = shared/coding-standards
codeContextProject = platform/headers:release-2.0
```
- `aiMaxConcurrentRequests`: Maximum number of concurrent requests sent to AI models across review workflows. The
  default value is `0`, which means unlimited. See
  [AI Request Coordination](architecture/request-coordination.md#concurrency-boundaries) for the distinction between
  model-request concurrency and durable request execution.
- `aiMaxMemoryTokens`: Maximum number of tokens retained in LangChain memory per Change, Patch Set, and review scope.
  The default value is 16K. The window also holds the prompt of the current request: a prompt larger than this value
  is evicted, and the provider receives an empty request (Gemini: `contents is not specified`). With
  `aiFullFileReview` and `maxReviewLines` in the thousands, set it well above the prompt size (for example `200000`);
  it limits the window, not the spend.
- `aiMaxToolResponseRounds`: Maximum number of tool-response continuation rounds allowed for one AI review request.
  This applies when ON_DEMAND code context tools are enabled and defaults to 3. Each round resends the whole
  conversation. In this fork every tool result ends with the number of rounds left, and tool calls requested after the
  last round are rejected with a request for the final answer (at most two more requests), so an exhausted budget still
  produces a review.
- `codeContextSearchScope` (fork): What the ON_DEMAND `grep` and `tree` tools cover. `CHANGED_FILES`, the default,
  covers only the files changed by the patch set (and the `codeContextProject` repositories), as upstream.
  `REPOSITORY` covers every file of the repository at the patch set: `grep` returns at most 30 matches, 3 per file,
  with lines cut at 160 characters, searches at most 5000 files, skips binary, LFS, excluded file types and files above
  512 KiB, and reports how many matches it left out; `tree` is no longer limited to the changed files. The tool
  descriptions tell the model which scope applies. `get_content` can read any file in both scopes; binary files are
  reported without content and text is cut at 64 KiB.
- `aiReviewUsageInMessage` (fork): When `true`, the review message ends with one line listing the models, the number of
  AI requests, the input and output tokens (thinking included) and the estimated cost of that review. A review group
  reports it once. The default value is `false`.
- `topicPatchSetWaitMs`: Time, in milliseconds, to wait when handling a Patch Set event for a change with a topic. This
  gives the plugin time to group related Patch Sets from the same topic and run an overall AI review. The default value
  is `3000` milliseconds.
- `topicReviewScope`: How changes of a topic are grouped for one review. `PROJECT_BRANCH`, the default, groups
  changes of the same project, branch and topic. `SUBMITTED_TOGETHER` reviews the set of changes that Gerrit submits
  together with the triggering change, across projects. See [Multi-Project Review Groups](#multi-project-review-groups).

### Optional Parameters Specific to Review Processing

- `multiAgentMode` (deprecated): This option allows for dividing the Patch Set review between two specialized agents:
  one focused to the Patch's code and another to the commit message. When this option is set to false (default value),
  the Patch Set review is unified into one single request processed by one agent instructed for both tasks.

- `agentSpecializationLevel`: Controls how review work is assigned to AI agents. This option overrides the deprecated
  `multiAgentMode` setting. Supported values are:
    * `SINGLE_AGENT`: Uses one agent to review both Patch Set code changes and the commit message. This is the default
      value and is equivalent to `multiAgentMode=false`.
    * `SCOPED_AGENTS`: Splits the review between dedicated Patch Set and Commit-Message agents. This is equivalent to
      `multiAgentMode=true`.
    * `SPECIALIZED_AGENTS`: Uses dedicated Patch Set review agents for correctness, testability, code quality,
      documentation, and security, based on prompts and workflow patterns imported from the Sashiko project
      (https://github.com/sashiko-dev/sashiko).

      When SPECIALIZED_AGENTS is selected, the Sashiko prompts override custom prompts, including those set through
      `aiRelevanceRules`, `aiSystemPromptInstructions`, `ai-instructions.md`, and prompts imported from Gerrit.

  See [Review Agent Architecture](architecture/review-agents.md) for the execution flow at each level, the concern
  lifecycle, and the differences between initial and subsequent reviews.

**NOTE**: Enabling these features may send multiple AI requests for a single review, which might increase AI API usage
costs.

### Optional Parameters Specific to OpenAI Provider

- `aiProviderZdr`: Enables Zero Data Retention (ZDR) mode for the OpenAI provider. When set to `true`, the plugin uses
  the Responses API with `store: false`, keeps conversation state in local plugin memory, and replays encrypted
  reasoning items between tool-call rounds. OpenAI Conversations are not used. This applies to every OpenAI model.
  The default value is `false`.

### Optional Parameters Specific to Ollama

- `ollamaDomain`: Defines the Ollama server endpoint. The default value is `http://localhost:11434`.
- `ollamaContextWindow`: Sets Ollama `num_ctx`, the model context window size. The default value is 16K.
- `ollamaResponseLength`: Sets Ollama `num_predict`, the maximum generated response length. The default value is `-1`.
- `ollamaThink`: Sets Ollama `think`, enabling thinking mode for supported models. The default value is `false`.

### Advanced Connection Parameters

These parameters should only be modified by advanced users:

- `aiConnectionTimeout`: Defines the timeout for connections to the OpenAI server, with a default of 30 seconds.
- `aiConnectionMaxRetryAttempts`: Determines the maximum number of retry attempts, defaulting to 2.
- `aiUploadedChunkSizeMb`: Sets the maximum size, in MB, of repository-content chunks built by the plugin when it needs
  to serialize repository files. The default value is 5 MB.
