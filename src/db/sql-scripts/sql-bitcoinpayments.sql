USE [TradeManagement]
GO
        CREATE TABLE [dbo].[BitcoinPayments] (
                [Id] nvarchar(36) NOT NULL,
                [AccountId] INT NOT NULL,
                [IdempotencyKey] nvarchar(128) NOT NULL,
                [FiatAmount] DECIMAL(18, 2) NOT NULL,
                [FiatCurrency] VARCHAR(3) NOT NULL,
                [AmountBtc] DECIMAL(18, 8) NOT NULL,
                [BtcAddress] nvarchar(96) NOT NULL,
                [Status] VARCHAR(20) NOT NULL CHECK(
                        Status IN('payment_created', 'payment_confirmed', 'payment_failed')
                ),
                [CreatedAt] datetimeoffset(0) NOT NULL,
                CONSTRAINT [PK_BitcoinPayments] PRIMARY KEY ([Id]),
                CONSTRAINT [UQ_BitcoinPayments_IdempotencyKey] UNIQUE ([IdempotencyKey]),
                CONSTRAINT [FK_BitcoinPayments_Accounts] FOREIGN KEY ([AccountId]) REFERENCES Accounts([Id])
        )
GO
