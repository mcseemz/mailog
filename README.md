# mailog
Rulebased mail manager for processing templated logs on email.<br>Build for those whose production logs/alerts are sent to email.

version 0.1-alpha

Currently connects to all specified folders on specified mailboxes, parses all messages, rule-matching them.<br>
Matched records added to templates. When template contains required minimum of records it sends packed report on specified email and purges source messages.<br>
After all messages are processed process finishes.

Plans:<ol>
<li>Folder monitors for continuous email supervising.</li>
<li>Alerts based on rule-matched email intensity / content.</li>
<li>Semiautomatc learning mode - understanding templates.</li>
</ol>

Any feedback welcome.
JSON description will be added soon
