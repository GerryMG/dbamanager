"""endurecimiento de seguridad: sello de aprobación, TLS por conexión, vencimiento de ejecución propia

Revision ID: 0004
Revises: 0003
"""
from alembic import op
import sqlalchemy as sa

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("approvals", sa.Column("content_hash", sa.String(64), nullable=True))
    op.add_column("db_connections", sa.Column("use_tls", sa.Boolean(), nullable=False, server_default=sa.false()))
    op.add_column("user_settings", sa.Column("can_execute_until", sa.DateTime(timezone=True), nullable=True))


def downgrade() -> None:
    op.drop_column("user_settings", "can_execute_until")
    op.drop_column("db_connections", "use_tls")
    op.drop_column("approvals", "content_hash")
