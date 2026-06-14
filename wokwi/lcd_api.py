class LcdApi:
    LCD_CLR = 0x01
    LCD_HOME = 0x02

    def clear(self):
        self.hal_write_command(self.LCD_CLR)

    def move_to(self, col, row):
        addr = col + (0x40 * row)
        self.hal_write_command(0x80 | addr)

    def putstr(self, string):
        for char in string:
            self.hal_write_data(ord(char))