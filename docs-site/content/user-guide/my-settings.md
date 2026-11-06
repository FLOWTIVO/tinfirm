# My settings for an agent

**My settings** is your own narrowing of an agent you may use: which runtime and model run your turns, who pays for them, instructions of your own, the systems, tools and skills you switch off, and the tools that ask you first. This page is for every member who talks to agents.

Your settings apply to your own conversations only: those you start in Tinfirm, from your AI client and in a chat's direct messages. A trigger has settings of its own, which its author sets ([Write a trigger](triggers.md#narrowed-for-its-runs)).

## Open your settings

On **Agents**, choose **My settings** on the agent's row, or open the agent and choose **My settings**. The page is called **My settings for {agent}**: *Narrows {agent} for your own conversations. It never lets it do more than its owner allowed.*

Until you save anything, the page says **Nothing saved yet: these are the defaults.** You keep settings only of an agent you may use: your own, or one shared with you or your groups. For any other, the page says whom to ask.

Beside the settings, **What this means for you** shows, for your next conversation, who you act as on each of the agent's systems (**Acts as**), who pays (**Pays**) and where the turns run (**Runs on**). After a change, it is **Read again after you save**.

## What a setting can and cannot do

Settings narrow the agent. They never widen it.

| A setting can | A setting cannot |
|---|---|
| Switch one of the agent's systems off for you | Add a system, a tool, a repository or a network host the agent does not list |
| Make a tool ask you first, or switch it off | Let a tool run unasked that the agent says needs approval |
| Switch a skill off for you | Raise a repository to read and write |
| Add instructions after the agent's | Remove or override the agent's instructions |
| Pick a runtime and a model among those allowed | Pick a runtime the agent does not list, or a model your model access does not allow |
| Add your own MCP servers | Add anyone else's MCP server |

Your own MCP servers are the one thing settings add, and only what is yours ([below](#your-own-mcp-servers)).

When the agent's owner removes something, it is gone for you from your next turn, whatever your settings say. When they add something, you get it only after you accept their new version ([below](#accept-a-new-version)). A save is checked against the agent as it is now: if the agent changed while you edited, the save is refused with **Your settings can narrow the agent, never widen it.** Reload the page and save again.

## Runtime and who pays

*Fixed when a conversation starts: your open conversations keep the ones they started with.*

| Setting | What it does |
|---|---|
| **Runtime** | What runs the agent's turns, among the agent's runtimes. The agent's default is marked **(default)**. |
| **Who pays** | **Your budget**, which pays through the company's model gateway and shows what it has left; or a subscription of yours on one of your own runner hosts, such as **Claude subscription on {host}** or **ChatGPT subscription on {host}** (an Anthropic or OpenAI API key on your host shows the same way). A subscription is offered only when it serves the runtime you picked, and pays only on its own host. |

A subscription shows its state when it cannot pay right now: **Cooling down: turns wait until it can be used again**, **Its host says it no longer works**, or **Its host is offline**. If the subscription in your settings is gone from your host, new conversations are refused until you pick another, or your budget.

When the company's model gateway is not set up yet, your budget cannot pay, and only a subscription can. Connect a runner host of your own, and see what your budget has left, on **Accounts** ([Your budget and spend](spend.md)).

## Model, instructions and defaults

*Saved changes reach your open conversations too, from their next turn.*

| Setting | What it does |
|---|---|
| **Model** | Among the models your model access allows. Your budget pays only for a model, so with your budget a model is required. With a subscription you may leave **The runtime's default**. |
| **Effort** | How much the runtime thinks before it acts: **The runtime's default**, **Low**, **Medium**, **High**, **Extra high** or **Maximum**. |
| **Added instructions** | Added after the agent's own instructions in every prompt, for example *Answer in German. Keep briefs under a page.* Up to 20,000 characters. |
| **Memory** | **Remember across your conversations**: the agent keeps short notes of what it learns in your conversations, such as how you work, your repositories and your systems. Only you see them, and only your conversations read them. Off keeps the notes, unread and unwritten. Offered only while the agent's editor allows memory. [Agent memory](memory.md) says more. |
| **Automatic retries** | **Retry your failed turns by themselves**: a turn that fails because the model's provider is overloaded or unavailable, or its runner host was lost, is retried up to three times, after 1, 2 and 4 minutes. Each retry is paid as the turn was. You hear only of the failure that ends the retries. Off by default. |
| **Default repositories** | A new conversation's workspace, among the agent's repositories. None picked: every repository of the agent. |
| **Default projects** | For each of the agent's trackers, where a new conversation looks first: Jira project keys such as `SHOP, OPS`, or `acme/shop` on GitHub and GitLab. Separate them with commas. |

The **Memory** panel below the settings lists the notes the agent keeps for you, and lets you delete them.

## Systems and tools

*Narrow what {agent} may do for you: switch a system off, or make a tool ask you first. Open conversations take this at their next turn.*

Each of the agent's systems has a switch. Off, it is **Off for your conversations**: the agent cannot call its tools in your conversations, and its repositories stay out of your workspace. A system's line sums up its tools as you leave them, for example **2 writes ask · 3 reads run**.

Open a system to choose, for each tool:

| Choice | What happens |
|---|---|
| **As the agent says** | The agent's mode applies: it runs, or it needs your approval. |
| **Ask me** | Each call waits for your approval, even where the agent allows it. |
| **Off** | The agent cannot call it in your conversations. |

A tool you narrowed shows **Narrowed**. Each tool's line says its effect and what the agent does with it, such as **Writes · Business analyst asks you**.

**Ask before every write** sets **Ask me** on every write and unconfirmed tool you left as the agent says. *Settings never let a tool run that the agent asks for.*

A tool that asks waits for your approval in your **Inbox** and in the conversation ([Your inbox](inbox.md)).

## Your own MCP servers

*Add an MCP server of your own: only you see it, and its tools act with your account.*

The servers you registered yourself are listed here; you register one under **Accounts**, when the company allows members their own servers ([MCP servers](../admin-guide/mcp-servers.md)). Switch one on to add it to the agent for your conversations. Each of its tools is **Allowed** or **Needs your approval**; *its reads are allowed and its writes need your approval, as it serves them*, until you change them. A tool you allowed while it read, and which now writes, needs your approval again until you allow it once more.

Your own servers reach only your conversations, never anyone else's. In a trigger, they are offered only while every account of the trigger is your own. You can add up to 20.

## Skills

Each of the agent's skills is **Read when a task fits** or, switched off, **Off for your conversations**. Your open conversations take the change at their next turn. [Skills](skills.md) explains what a skill is.

## Save, discard, or go back to the defaults

A bar at the bottom counts your **unsaved changes** and names them. **Save** stores them: *Your conversations take them at their next turn.* **Discard** drops them.

**Back to defaults**, at the top, asks **Back to the defaults?**: your model, effort, added instructions, default repositories and projects, every system and tool you narrowed and every skill you switched off go back to what the agent's owner set, from each conversation's next turn. New conversations start with the default runtime and payer. The version you accepted stays.

The runtime and who pays never change in a conversation that is already open: start a new conversation to use new ones.

## Accept a new version

When the agent's owner makes a version that may do more, your settings page and the agent's page show **{agent} has a new version to accept**, with what it adds: systems, tools, repositories and network hosts. Until you choose **Accept version N**, your turns keep the version you accepted, without the additions. What the owner removed applies already.

Meanwhile, the additions show in your settings, disabled, as **Yours after you accept version N**. Once you accept, **Version N accepted. Your conversations use it from their next turn.**

[Make and share an agent](agents.md#change-an-agent-versions-widening-and-narrowing) says what counts as doing more.
