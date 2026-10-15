"""versión del servidor por conexión y avance de ejecución por operación

Revision ID: 0002
Revises: 0001
"""
from alembic import op
import sqlalchemy as sa

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("db_connections", sa.Column("server_version", sa.String(40), nullable=True))
    op.add_column("request_operations", sa.Column("statements_done", sa.Integer(), nullable=False, server_default="0"))


def downgrade() -> None:
    op.drop_column("request_operations", "statements_done")
    op.drop_column("db_connections", "server_version")
