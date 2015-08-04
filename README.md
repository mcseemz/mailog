# mailog
Rulebased mail manager for processing templated logs on email.<br>Build for those whose production logs/alerts are sent to email.

version 0.1-alpha

Currently works in two modes - archiver and monitor.
<b>Archiver</b> connects to all specified folders on specified mailboxes, parses all messages, rule-matching them.<br>
Matched records added to templates. When template contains required minimum of records it sends packed report on specified email and purges source messages.<br>
After all messages are processed process finishes.

<b>Monitor</b> looks for folders activity using the same rule-matching logic. 

Both modes can work simultaneously, mixing results in one report letter.

Report letters are preficed as "RPT" so they are skipped from matching.

Plans:<ol>
<li>Alerts based on rule-matched email intensity / content.</li>
<li>Semiautomatc learning mode - understanding templates.</li>
</ol>

Any feedback welcome.
JSON description will be added soon
