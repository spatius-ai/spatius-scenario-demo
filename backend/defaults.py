"""Defaults shared by server.py and agent.py.

Kept in its own file rather than in server.py because agent.py is a separate process,
and importing from server would bring the Flask app up with it.
"""

# A public sample avatar that any account can load. Taken from characters.ts in the
# official spatius-avatar-demo repo, and the same one (Kian) as the first entry in the
# config page's list — if the two drift apart, the avatar shown in the UI and the one
# actually rendered become different people.
DEFAULT_AVATAR_ID = "41c62a7c-993c-4b6b-b6d3-549ce3c8be00"
