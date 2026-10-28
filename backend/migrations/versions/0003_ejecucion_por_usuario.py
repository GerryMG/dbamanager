"""ejecución desde la app activada por cada DBA para sí mismo

Revision ID: 0003
Revises: 0002
"""
from alembic import op
import sqlalchemy as sa

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "user_settings",
        sa.Column("username", sa.String(150), primary_key=True),
        sa.Column("can_execute", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("can_execute_changed_at", sa.DateTime(timezone=True), nullable=True),
    )


def downgrade() -> None:
    op.drop_table("user_settings")
