# AI Code Review Gerrit Plugin

> **This is a fork** of [amarula/reviewai-gerrit-plugin](https://github.com/amarula/reviewai-gerrit-plugin),
> maintained by Phoenix Systems for its Gerrit. Branch `phoesys/main` is upstream `main` plus the changes below.
> Bug fixes go upstream as pull requests; the features are kept here. Every addition is off by default, so with no
> new settings the plugin behaves like upstream.

## Fork additions

| Setting | What it adds |
|---|---|
| `commitMessageDirective` | Mandatory rules that apply only to the commit-message review, so code reviewers don't report them against code. |
| `aiProjectInstructionsInReviews` | Adds the target branch's `.gerrit/ai-instructions.md` to patch-set reviews, not only to chat replies. |
| `topicReviewScope = SUBMITTED_TOGETHER` | Reviews everything Gerrit submits together (a topic across repositories, e.g. a superproject gitlink bump and its submodule changes) as one review, with per-repository path prefixes and readable submodule updates. See [Multi-Project Review Groups](docs/configuration.md#multi-project-review-groups). |
| `codeContextProject` | Read-only extra repositories (coding standards, shared headers, specifications) that the ON_DEMAND tools can browse under `reviewai-context/<project>/`. |
| `aiBudgetDailyUsd`, `aiBudgetMonthlyUsd`, `aiBudgetProjectMonthlyUsd` | Budgets on the estimated cost: automatic reviews stop at 100 %, manual requests at 120 %. Admin endpoint `ai-usage` reports the spend. See [AI Budgets](docs/configuration.md#ai-budgets). |
| telemetry | A `project` field on review-run and cost metrics, plus `ai_request/project_count`. See [telemetry](docs/telemetry.md). |
| `codeContextSearchScope = REPOSITORY` | ON_DEMAND `grep` and `tree` cover the whole repository at the patch set instead of only the changed files, with bounded results; the tool descriptions state the scope. See [codeContextSearchScope](docs/configuration.md#optional-parameters). |
| `aiReviewUsageInMessage` | Ends the review message with the review's models, number of AI requests, input/output tokens and estimated cost. |
| tool budget | Every tool result tells the model how many tool rounds are left; calls past `aiMaxToolResponseRounds` are rejected with a request for the final answer, instead of ending the review without one. Always on. |
| Review Agent action | "Review With Related Changes" runs `/review --topic`; its hover text shows the `maxReviewLines` limit. |
| `/help` | Lists only the commands the user can run in this build and role. |
| group review over `maxReviewLines` | When a review group is reduced to the triggering change, the review message says so, with the group's size and the limit. |
| Maven dev build | `mvn -Pdev package` builds the development variant (`DevModule`: `/show`, `/directives`, `/configure`, `--debug`). |

Bug fixes also in this fork, submitted upstream:

- With `codeContextPolicy=NONE`, commit-message reviews dropped the first `directive`.
- In `SPECIALIZED_AGENTS` mode, `directive` values didn't reach the reviewing agents.

Fixes kept in this fork for now (fork-specific code paths or not yet proposed upstream):

- Gemini 3: tool rounds failed with "Function call is missing a thought_signature"; the Gemini client now keeps and
  resends thought signatures (`returnThinking`, `sendThinking`).
- Gemini 3 thinking tokens are billed as output but were not in the cost estimate (LangChain4j reports them only in the
  total). The estimate now bills `total - input` as output, and each priced response is logged with its token usage.
- Commit-message comments ended up at patch-set level: `/COMMIT_MSG` is now anchored in reviews (not only in suggest
  mode), replies of the commit-message agent are pinned to the commit message (in a merged review group to the member
  whose commit message they quote), `COMMIT_MSG` without the slash is accepted, and a comment is anchored to the lines
  it quotes (e.g. the subject) instead of the whole message; suggested edits still cover the whole message.
- `get_content` returned binary files as text (a 500 KB flash loader added 155k tokens to every later request) and did
  not limit size; binary and LFS files are now reported without content and text is cut at 64 KB.
- Group reviews used the comments of the last group member for the reviewed change ("Pending review feedback comment is
  missing from Gerrit" on `/review --topic`).
- Empty `grep`/`tree` results now say that they only cover the changed files, instead of `CONTEXT NOT PROVIDED`, which
  made the model retry the same search.

Build (Maven, production variant; add `-Pdev` for the development variant):

```bash
docker run --rm -u $(id -u):$(id -g) -e MAVEN_CONFIG=/var/maven/.m2 -v $HOME/.m2:/var/maven/.m2 \
  -v "$PWD":/src -w /src maven:3.9.9-eclipse-temurin-21 \
  mvn -B -Duser.home=/var/maven -Dmaven.repo.local=/var/maven/.m2/repository -DGerrit-ApiVersion=3.14.2 clean package
```

---

## Features

This plugin adds ReviewAI support to Gerrit through the Review Agent sidebar, giving users a standard chatbot interface
inside the Gerrit change page. From the sidebar, users can ask questions about the Change, select the AI model, and keep
conversation history tied to the review.

ReviewAI can also review Patch Sets automatically, posting feedback as Gerrit comments and, optionally, a vote. Users
can continue the discussion in Gerrit comments by mentioning `@{gerritUserName}` or `@{gerritEmailAddress}`
(provided that `gerritEmailAddress` is in the form `gerritUserName@<any_email_domain>`), trigger reviews with
`/review`, and view command help with `/help` or `/help <command>`.

## Commercial Support

For commercial support, please contact Amarula Solutions at https://www.amarulasolutions.com/quotation/

## Getting Started

### Build

This version requires JDK 21 and Bazel. From the Gerrit checkout, link the plugin Bazel dependencies, update the module
lockfile, and build the production plugin:

```bash
cd gerrit/plugins
rm external_plugin_deps.MODULE.bazel
ln -s reviewai-gerrit-plugin/external_plugin_deps.MODULE.bazel external_plugin_deps.MODULE.bazel

cd ..
bazel mod deps --lockfile_mode=update
bazelisk build plugins/reviewai-gerrit-plugin:reviewai-gerrit-plugin
```

The generated JAR is available under Gerrit's `bazel-bin/plugins/reviewai-gerrit-plugin/` directory. See
[Development and Debugging](docs/development.md) for the development build and test commands.

### Install

Upload `reviewai-gerrit-plugin.jar` to the `$gerrit_site/plugins` directory.

### Minimal Configuration

Create an AI user in Gerrit, then add the following settings to `$gerrit_site/etc/gerrit.config`:

```ini
[plugin "reviewai-gerrit-plugin"]
    gerritUserName = {gerritUserName}
    aiTokens = OpenAI/{openAiToken}
```

`aiProviders` and `aiModels` are optional. If omitted, the plugin exposes the default OpenAI model routes. Sensitive
values such as `aiTokens` should be stored in `$gerrit_site/etc/secure.config`. See
[Configuration](docs/configuration.md) for provider routes, project-level settings, and the complete parameter
reference.

### Verify

After restarting Gerrit, confirm that its logs contain an entry similar to:

```text
INFO com.google.gerrit.server.plugins.PluginLoader : Loaded plugin reviewai-gerrit-plugin, version ...
```

The Gerrit plugin page should also show `reviewai-gerrit-plugin` as enabled.

## Usage Examples

### Review Agent Sidebar

The Review Agent is available from the Gerrit change page sidebar. It provides quick actions for full reviews, as well
as scoped Patch Set and Commit Message reviews.

<kbd><img src="images/reviewai-sidebar.png?raw=true" alt="Review Agent sidebar"></kbd>

### Model Selection

The sidebar allows users to select the AI provider and model used for the review.

<kbd><img src="images/reviewai-sidebar-model_dropdown.png?raw=true" alt="ReviewAI model selection"></kbd>

### Automatic Reviews

When a Patch Set is submitted, ReviewAI can automatically review the change and publish findings. In this example, the
review produces a `Code-Review -1` recommendation.

See [Review Agent Architecture](docs/architecture/review-agents.md) for the agent-specialization levels and the concern
lifecycle across successive Patch Sets.

See [CI Integration](docs/ci-integration.md) to defer reviews until Jenkins, SonarQube, or another CI system has
voted on the Patch Set.

<kbd><img src="images/reviewai-sidebar-review.png?raw=true" alt="ReviewAI Patch Set review"></kbd>

Voting is disabled by default. Enable it globally or per project with the `enabledVoting` configuration option.

### Follow-up Interaction

Users can continue the conversation with ReviewAI from the sidebar. In this example, the user asks for a full commit
message based on the previous review, and ReviewAI replies in the same conversation.

<kbd><img src="images/reviewai-sidebar-message_reply.png?raw=true" alt="ReviewAI follow-up message"></kbd>

### Gerrit Change Log

ReviewAI comments are published directly in Gerrit, including patch-set-level feedback and inline comments on the
affected files. This keeps review feedback and further AI interactions available in the Gerrit change log.

<kbd><img src="images/reviewai-gerrit_change_log-review.png?raw=true" alt="ReviewAI comments in Gerrit change log"></kbd>

More examples of AI code reviews and inline discussions are available on the
[ReviewAI project page](https://wiki.amarulasolutions.com/opensource/products/chatgpt-gerrit.html).

## AI Providers

ReviewAI supports OpenAI, Gemini, DeepSeek, MoonShot, and Ollama through provider/model routes such as
`OpenAI/gpt-5.4` and `Ollama/llama3.2`. OpenAI is the default provider; OpenAI, Gemini, DeepSeek, and MoonShot require
provider tokens, while Ollama does not.

See [Configuration](docs/configuration.md#ai-provider-routes) for model selection, tokens, default routes, and custom
endpoints.

## Commands

Messages and commands can be entered directly in the Review Agent sidebar:

```text
> Explain the purpose of this change.

> /review

> /review --scope=commit_message

> /suggest

> /help
```

They can also be sent through traditional Gerrit comments by addressing the configured AI user, for example
`@gpt /review`.

See the [Command Reference](docs/commands.md) for every command, option, scope, and the AI Moderator and ReviewAI
Administrator roles.

## Documentation

- [Review Agent Architecture](docs/architecture/review-agents.md)
- [AI Request Coordination](docs/architecture/request-coordination.md)
- [Configuration](docs/configuration.md)
- [Command Reference](docs/commands.md)
- [Development and Debugging](docs/development.md)
- [Telemetry](docs/telemetry.md)

## License

Apache License 2.0
