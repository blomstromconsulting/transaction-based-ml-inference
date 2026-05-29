from feast import Entity

customer = Entity(
    name="customer_id",
    join_keys=["customer_id"],
    description="Customer aggregation key for online fraud statistics.",
)

merchant = Entity(
    name="merchant_id",
    join_keys=["merchant_id"],
    description="Merchant key for optional merchant risk statistics.",
)
