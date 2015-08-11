# mailog
Rulebased mail manager for processing templated logs on email.<br>Made for people whose production logs/alerts are sent to email.

version 0.2-beta

Works in two modes - archiver and monitor.<br>
<b>Archiver</b> connects to all specified folders on specified mailboxes, parses all messages one-time, rule-matching them.<br>
<b>Monitor</b> looks for current folders activity using the same rule-matching logic.

Both modes can work simultaneously, mixing results in one report letter.

Report letters are prefixed as "RPT" so they are skipped from further matching.

<h3>High-level mechanics</h3>
IMAP does no allow to filter server-side using regexp, so all emails downloaded.

1. Email body split to sections and these sections are used to fill fieldsets along with fields parsed from subject.<br>
2. Each fieldset passed to according template's report.<br>
3. After template record limit reached, report email sent to specified mailbox. Then all participated emails deleted.<br>

<h3>Rules and Templates description</h3>
for detailed commented description check <a href='json-description.txt'>json-description.txt</a>

<h3>Sections</h3>
TBD


<h3>Plans</h3>
<ol>
<li>Alerts based on rule-matched email intensity / content.</li>
<li>Semiautomatc learning mode - understanding templates.</li>
<li>autoreload mode</li>
</ol>

Any feedback welcome.